/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.connect.mirror;

import org.apache.kafka.common.config.AbstractConfig;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.common.config.ConfigDef.Type;
import org.apache.kafka.common.config.ConfigDef.Importance;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.common.security.auth.SecurityProtocol;
import org.apache.kafka.common.utils.Utils;

import java.util.Map;
import java.util.HashMap;

import static org.apache.kafka.common.config.ConfigDef.CaseInsensitiveValidString.in;

/** Configuration required for MirrorClient to talk to a given target cluster.
 *  <p>
 *  Generally, these properties come from an mm2.properties configuration file
 *  (@see MirrorMakerConfig.clientConfig):
 *  </p>
 *  <pre>
 *    MirrorMakerConfig mmConfig = new MirrorMakerConfig(props);
 *    MirrorClientConfig mmClientConfig = mmConfig.clientConfig("some-cluster");
 *  </pre>
 *  <p>
 *  In addition to the properties defined here, sub-configs are supported for Admin, Consumer, and Producer clients.
 *  For example:
 *  </p>
 *  <pre>
 *      bootstrap.servers = host1:9092
 *      consumer.client.id = mm2-client
 *      replication.policy.separator = __
 *  </pre>
 */
public class MirrorClientConfig extends AbstractConfig {
    public static final String REPLICATION_POLICY_CLASS = "replication.policy.class";
    private static final String REPLICATION_POLICY_CLASS_DOC = "Class which defines the remote topic naming convention.";
    public static final Class<?> REPLICATION_POLICY_CLASS_DEFAULT = DefaultReplicationPolicy.class;
    public static final String REPLICATION_POLICY_SEPARATOR = "replication.policy.separator";
    private static final String REPLICATION_POLICY_SEPARATOR_DOC = "Separator used in remote topic naming convention.";
    public static final String REPLICATION_POLICY_SEPARATOR_DEFAULT =
        DefaultReplicationPolicy.SEPARATOR_DEFAULT;
    public static final String INTERNAL_TOPIC_SEPARATOR_ENABLED =  "replication.policy.internal.topic.separator.enabled";
    public static final String INTERNAL_TOPIC_SEPARATOR_ENABLED_DOC =
            "Whether to use replication.policy.separator to control the names of topics used for checkpoints and offset syncs. " +
                    "By default, custom separators are used in these topic names; however, if upgrading MirrorMaker 2 from older versions " +
                    "that did not allow for these topic names to be customized, it may be necessary to set this property to 'false' in order " +
                    "to continue using the same names for those topics.";
    public static final Boolean INTERNAL_TOPIC_SEPARATOR_ENABLED_DEFAULT =
        DefaultReplicationPolicy.INTERNAL_TOPIC_SEPARATOR_ENABLED_DEFAULT;

    public static final String ADMIN_CLIENT_PREFIX = "admin.";
    public static final String CONSUMER_CLIENT_PREFIX = "consumer.";
    public static final String PRODUCER_CLIENT_PREFIX = "producer.";

    MirrorClientConfig(Map<?, ?> props) {
        super(CONFIG_DEF, props, true);
    }

    public ReplicationPolicy replicationPolicy() {
        return getConfiguredInstance(REPLICATION_POLICY_CLASS, ReplicationPolicy.class);
    }

    /** Sub-config for Admin clients. */
    public Map<String, Object> adminConfig() {
        return clientConfig(ADMIN_CLIENT_PREFIX);
    }

    /** Sub-config for Consumer clients. */
    public Map<String, Object> consumerConfig() {
        return clientConfig(CONSUMER_CLIENT_PREFIX);
    }

    /** Sub-config for Producer clients. */
    public Map<String, Object> producerConfig() {
        return clientConfig(PRODUCER_CLIENT_PREFIX);
    }
    
    private Map<String, Object> clientConfig(String prefix) {
        Map<String, Object> props = new HashMap<>();
        props.putAll(valuesWithPrefixOverride(prefix));
        props.keySet().retainAll(CLIENT_CONFIG_DEF.names());
        props.entrySet().removeIf(x -> x.getValue() == null);
        return props;
    }

    // Properties passed to internal Kafka clients
    static final ConfigDef CLIENT_CONFIG_DEF = new ConfigDef()
        .define(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG,
            Type.LIST,
            null,
            Importance.HIGH,
            CommonClientConfigs.BOOTSTRAP_SERVERS_DOC)
        // security support
        .define(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG,
            Type.STRING,
            CommonClientConfigs.DEFAULT_SECURITY_PROTOCOL,
            in(Utils.enumOptions(SecurityProtocol.class)),
            Importance.MEDIUM,
            CommonClientConfigs.SECURITY_PROTOCOL_DOC)
        .withClientSslSupport()
        .withClientSaslSupport();
 
    static final ConfigDef CONFIG_DEF = new ConfigDef()
        .define(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG,
            Type.STRING,
            null,
            Importance.HIGH,
            CommonClientConfigs.BOOTSTRAP_SERVERS_DOC) 
        .define(
            REPLICATION_POLICY_CLASS,
            ConfigDef.Type.CLASS,
            REPLICATION_POLICY_CLASS_DEFAULT,
            ConfigDef.Importance.LOW,
            REPLICATION_POLICY_CLASS_DOC)
        .define(
            REPLICATION_POLICY_SEPARATOR,
            ConfigDef.Type.STRING,
            REPLICATION_POLICY_SEPARATOR_DEFAULT,
            ConfigDef.Importance.LOW,
            REPLICATION_POLICY_SEPARATOR_DOC)
        .define(
            INTERNAL_TOPIC_SEPARATOR_ENABLED,
            ConfigDef.Type.BOOLEAN,
            INTERNAL_TOPIC_SEPARATOR_ENABLED_DEFAULT,
            ConfigDef.Importance.LOW,
            INTERNAL_TOPIC_SEPARATOR_ENABLED_DOC)
        .define(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG,
                Type.STRING,
                CommonClientConfigs.DEFAULT_SECURITY_PROTOCOL,
                in(Utils.enumOptions(SecurityProtocol.class)),
                Importance.MEDIUM,
                CommonClientConfigs.SECURITY_PROTOCOL_DOC)
        .withClientSslSupport()
        .withClientSaslSupport();
}
