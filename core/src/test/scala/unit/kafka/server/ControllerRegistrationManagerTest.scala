/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/

package kafka.server

import org.apache.kafka.common.{Node, Uuid}
import org.apache.kafka.common.message.ControllerRegistrationResponseData
import org.apache.kafka.common.metadata.{FeatureLevelRecord, RegisterControllerRecord}
import org.apache.kafka.common.requests.ControllerRegistrationResponse
import org.apache.kafka.image.loader.{LogDeltaManifest, SnapshotManifest}
import org.apache.kafka.image.{MetadataDelta, MetadataImage, MetadataProvenance}
import org.apache.kafka.metadata.{RecordTestUtils, VersionRange}
import org.apache.kafka.raft.LeaderAndEpoch
import org.apache.kafka.server.common.MetadataVersion
import org.apache.kafka.test.TestUtils
import org.junit.jupiter.api.Assertions.{assertEquals, assertFalse, assertTrue}
import org.junit.jupiter.api.{Test, Timeout}
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

import java.util
import java.util.{OptionalInt, Properties}
import java.util.concurrent.{CompletableFuture, TimeUnit}

@Timeout(value = 60)
class ControllerRegistrationManagerTest {
  private val controller1 = new Node(1, "localhost", 7000)

  private def configProperties = {
    val properties = new Properties()
    properties.setProperty(KafkaConfig.LogDirsProp, "/tmp/foo")
    properties.setProperty(KafkaConfig.ProcessRolesProp, "controller")
    properties.setProperty(KafkaConfig.ListenerSecurityProtocolMapProp, s"CONTROLLER:PLAINTEXT")
    properties.setProperty(KafkaConfig.ListenersProp, s"CONTROLLER://localhost:0")
    properties.setProperty(KafkaConfig.ControllerListenerNamesProp, "CONTROLLER")
    properties.setProperty(KafkaConfig.NodeIdProp, "1")
    properties.setProperty(KafkaConfig.QuorumVotersProp, s"1@localhost:8000,2@localhost:5000,3@localhost:7000")
    properties
  }

  private def createSupportedFeatures(
    highestSupportedMetadataVersion: MetadataVersion
  ): java.util.Map[String, VersionRange] = {
    val results = new util.HashMap[String, VersionRange]()
    results.put(MetadataVersion.FEATURE_NAME, VersionRange.of(
      MetadataVersion.MINIMUM_KRAFT_VERSION.featureLevel(),
      highestSupportedMetadataVersion.featureLevel()))
    results
  }

  private def newControllerRegistrationManager(
    context: RegistrationTestContext,
  ): ControllerRegistrationManager = {
    new ControllerRegistrationManager(context.config,
      context.clusterId,
      context.time,
      "controller-registration-manager-test-",
      createSupportedFeatures(MetadataVersion.IBP_3_6_IV2),
      () => context.controllerEpoch.get(),
      RecordTestUtils.createTestControllerRegistration(1, false).incarnationId())
  }

  private def registered(manager: ControllerRegistrationManager): Boolean = {
    val registered = new CompletableFuture[Boolean]
    manager.eventQueue.append(() => {
      registered.complete(manager.registered)
    })
    registered.get(30, TimeUnit.SECONDS)
  }

  private def rpcStats(manager: ControllerRegistrationManager): (Long, Long, Long) = {
    val failedAttempts = new CompletableFuture[(Long, Long, Long)]
    manager.eventQueue.append(() => {
      failedAttempts.complete((manager.pendingRpcs, manager.successfulRpcs, manager.failedRpcs))
    })
    failedAttempts.get(30, TimeUnit.SECONDS)
  }

  private def doMetadataUpdate(
    prevImage: MetadataImage,
    manager: ControllerRegistrationManager,
    metadataVersion: MetadataVersion,
    registrationModifier: RegisterControllerRecord => Option[RegisterControllerRecord]
  ): MetadataImage = {
    val delta = new MetadataDelta.Builder().
      setImage(prevImage).
      build()
    if (!prevImage.features().metadataVersion().equals(metadataVersion)) {
      delta.replay(new FeatureLevelRecord().
        setName(MetadataVersion.FEATURE_NAME).
        setFeatureLevel(metadataVersion.featureLevel()))
    }
    if (metadataVersion.isControllerRegistrationSupported) {
      for (i <- Seq(1, 2, 3)) {
        registrationModifier(RecordTestUtils.createTestControllerRegistration(i, false)).foreach {
          registration => delta.replay(registration)
        }
      }
    }
    val provenance = new MetadataProvenance(100, 200, 300)
    val newImage = delta.apply(provenance)
    val manifest = if (!prevImage.features().metadataVersion().equals(metadataVersion)) {
      new SnapshotManifest(provenance, 1000)
    } else {
      new LogDeltaManifest(provenance,
        new LeaderAndEpoch(OptionalInt.of(1), 100),
        1,
        100,
        200)
    }
    manager.onMetadataUpdate(delta, newImage, manifest)
    newImage
  }

  @Test
  def testCreateAndClose(): Unit = {
    val context = new RegistrationTestContext(configProperties)
    val manager = newControllerRegistrationManager(context)
    assertFalse(registered(manager))
    assertEquals((0, 0, 0), rpcStats(manager))
    manager.close()
  }

  @Test
  def testCreateStartAndClose(): Unit = {
    val context = new RegistrationTestContext(configProperties)
    val manager = newControllerRegistrationManager(context)
    try {
      manager.start(context.mockChannelManager)
      assertFalse(registered(manager))
      assertEquals((0, 0, 0), rpcStats(manager))
    } finally {
      manager.close()
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = Array(false, true))
  def testRegistration(metadataVersionSupportsRegistration: Boolean): Unit = {
    val context = new RegistrationTestContext(configProperties)
    val metadataVersion = if (metadataVersionSupportsRegistration) {
      MetadataVersion.IBP_3_6_IV2
    } else {
      MetadataVersion.IBP_3_6_IV0
    }
    val manager = newControllerRegistrationManager(context)
    try {
      if (!metadataVersionSupportsRegistration) {
        context.mockClient.prepareUnsupportedVersionResponse(_ => true)
      } else {
        context.controllerNodeProvider.node.set(controller1)
      }
      manager.start(context.mockChannelManager)
      assertFalse(registered(manager))
      assertEquals((0, 0, 0), rpcStats(manager))
      val image = doMetadataUpdate(MetadataImage.EMPTY,
        manager,
        metadataVersion,
        r => if (r.controllerId() == 1) None else Some(r))
      if (!metadataVersionSupportsRegistration) {
        assertFalse(registered(manager))
        assertEquals((0, 0, 0), rpcStats(manager))
      } else {
        TestUtils.retryOnExceptionWithTimeout(30000, () => {
          assertEquals((1, 0, 0), rpcStats(manager))
        })
        context.mockClient.prepareResponseFrom(new ControllerRegistrationResponse(
          new ControllerRegistrationResponseData()), controller1)
        TestUtils.retryOnExceptionWithTimeout(30000, () => {
          context.mockChannelManager.poll()
          assertEquals((0, 1, 0), rpcStats(manager))
        })
        assertFalse(registered(manager))
        doMetadataUpdate(image,
          manager,
          metadataVersion,
          r => Some(r))
        assertTrue(registered(manager))
      }
    } finally {
      manager.close()
    }
  }

  @Test
  def testWrongIncarnationId(): Unit = {
    val context = new RegistrationTestContext(configProperties)
    val manager = newControllerRegistrationManager(context)
    try {
      context.controllerNodeProvider.node.set(controller1)
      manager.start(context.mockChannelManager)
      context.mockClient.prepareResponseFrom(new ControllerRegistrationResponse(
        new ControllerRegistrationResponseData()), controller1)
      var image = doMetadataUpdate(MetadataImage.EMPTY,
        manager,
        MetadataVersion.IBP_3_6_IV2,
        r => if (r.controllerId() == 1) None else Some(r))
      TestUtils.retryOnExceptionWithTimeout(30000, () => {
        context.mockChannelManager.poll()
        assertEquals((0, 1, 0), rpcStats(manager))
      })
      image = doMetadataUpdate(image,
        manager,
        MetadataVersion.IBP_3_6_IV2,
        r => Some(r.setIncarnationId(new Uuid(456, r.controllerId()))))
      TestUtils.retryOnExceptionWithTimeout(30000, () => {
        assertEquals((1, 1, 0), rpcStats(manager))
      })
      context.mockClient.prepareResponseFrom(new ControllerRegistrationResponse(
        new ControllerRegistrationResponseData()), controller1)
      doMetadataUpdate(image,
        manager,
        MetadataVersion.IBP_3_6_IV2,
        r => Some(r))
      TestUtils.retryOnExceptionWithTimeout(30000, () => {
        context.mockChannelManager.poll()
        assertEquals((0, 2, 0), rpcStats(manager))
        assertTrue(registered(manager))
      })
    } finally {
      manager.close()
    }
  }
}