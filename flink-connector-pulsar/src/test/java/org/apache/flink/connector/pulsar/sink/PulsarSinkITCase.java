/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.connector.pulsar.sink;

import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.connector.base.DeliveryGuarantee;
import org.apache.flink.connector.pulsar.common.MiniClusterTestEnvironment;
import org.apache.flink.connector.pulsar.testutils.PulsarTestContextFactory;
import org.apache.flink.connector.pulsar.testutils.PulsarTestEnvironment;
import org.apache.flink.connector.pulsar.testutils.PulsarTestSuiteBase;
import org.apache.flink.connector.pulsar.testutils.function.ControlSource;
import org.apache.flink.connector.pulsar.testutils.runtime.PulsarRuntime;
import org.apache.flink.connector.pulsar.testutils.sink.cases.AutoCreateTopicProducingContext;
import org.apache.flink.connector.pulsar.testutils.sink.cases.EncryptedMessageProducingContext;
import org.apache.flink.connector.pulsar.testutils.sink.cases.MultipleTopicsProducingContext;
import org.apache.flink.connector.pulsar.testutils.sink.cases.SingleTopicProducingContext;
import org.apache.flink.connector.testframe.junit.annotations.TestContext;
import org.apache.flink.connector.testframe.junit.annotations.TestEnv;
import org.apache.flink.connector.testframe.junit.annotations.TestExternalSystem;
import org.apache.flink.connector.testframe.junit.annotations.TestSemantics;
import org.apache.flink.connector.testframe.testsuites.SinkTestSuiteBase;
import org.apache.flink.runtime.minicluster.RpcServiceSharing;
import org.apache.flink.runtime.testutils.MiniClusterResourceConfiguration;
import org.apache.flink.streaming.api.CheckpointingMode;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.test.junit5.MiniClusterExtension;
import org.apache.flink.testutils.junit.SharedObjectsExtension;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.apache.commons.lang3.RandomStringUtils.randomAlphabetic;
import static org.apache.flink.streaming.api.CheckpointingMode.AT_LEAST_ONCE;
import static org.apache.flink.streaming.api.CheckpointingMode.EXACTLY_ONCE;
import static org.apache.pulsar.client.api.Schema.STRING;
import static org.assertj.core.api.Assertions.assertThat;

/** Tests for using PulsarSink writing to a Pulsar cluster. */
class PulsarSinkITCase {

    /** Integration test based on the connector testing framework. */
    @Nested
    class IntegrationTest extends SinkTestSuiteBase<String> {

        @TestEnv MiniClusterTestEnvironment flink = new MiniClusterTestEnvironment();

        @TestExternalSystem
        PulsarTestEnvironment pulsar = new PulsarTestEnvironment(PulsarRuntime.container());

        @TestSemantics
        CheckpointingMode[] semantics = new CheckpointingMode[] {AT_LEAST_ONCE, EXACTLY_ONCE};

        @TestContext
        PulsarTestContextFactory<String, SingleTopicProducingContext> singleTopic =
                new PulsarTestContextFactory<>(pulsar, SingleTopicProducingContext::new);

        @TestContext
        PulsarTestContextFactory<String, MultipleTopicsProducingContext> multipleTopics =
                new PulsarTestContextFactory<>(pulsar, MultipleTopicsProducingContext::new);

        @TestContext
        PulsarTestContextFactory<String, AutoCreateTopicProducingContext> topicAutoCreation =
                new PulsarTestContextFactory<>(pulsar, AutoCreateTopicProducingContext::new);

        @TestContext
        PulsarTestContextFactory<String, EncryptedMessageProducingContext> encryptMessages =
                new PulsarTestContextFactory<>(pulsar, EncryptedMessageProducingContext::new);
    }

    /** Tests for using PulsarSink writing to a Pulsar cluster. */
    @Nested
    class DeliveryGuaranteeTest extends PulsarTestSuiteBase {

        private static final int PARALLELISM = 1;

        @RegisterExtension
        private final MiniClusterExtension clusterExtension =
                new MiniClusterExtension(
                        new MiniClusterResourceConfiguration.Builder()
                                .setNumberTaskManagers(1)
                                .setNumberSlotsPerTaskManager(PARALLELISM)
                                .setRpcServiceSharing(RpcServiceSharing.DEDICATED)
                                .withHaLeadershipControl()
                                .build());

        // Using this extension for creating shared reference which would be used in source
        // function.
        @RegisterExtension
        final SharedObjectsExtension sharedObjects = SharedObjectsExtension.create();

        @ParameterizedTest
        @EnumSource(DeliveryGuarantee.class)
        void writeRecordsToPulsar(DeliveryGuarantee guarantee) throws Exception {
            // A random topic with partition 4.
            String topic = randomAlphabetic(8);
            operator().createTopic(topic, 4);
            operator().createSchema(topic, STRING);
            int counts = ThreadLocalRandom.current().nextInt(100, 200);

            ControlSource source =
                    new ControlSource(
                            sharedObjects,
                            operator(),
                            topic,
                            guarantee,
                            counts,
                            Duration.ofMillis(50),
                            Duration.ofMinutes(5));
            PulsarSink<String> sink =
                    PulsarSink.builder()
                            .setServiceUrl(operator().serviceUrl())
                            .setAdminUrl(operator().adminUrl())
                            .setDeliveryGuarantee(guarantee)
                            .setTopics(topic)
                            .setSerializationSchema(new SimpleStringSchema())
                            .build();

            StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

            env.setParallelism(PARALLELISM);
            if (guarantee != DeliveryGuarantee.NONE) {
                env.enableCheckpointing(500L);
            }
            env.addSource(source).sinkTo(sink);
            env.execute();

            List<String> expectedRecords = source.getExpectedRecords();
            List<String> consumedRecords = source.getConsumedRecords();

            assertThat(consumedRecords)
                    .hasSameSizeAs(expectedRecords)
                    .containsExactlyInAnyOrderElementsOf(expectedRecords);
        }
    }
}
