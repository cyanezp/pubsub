// Copyright 2016 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
////////////////////////////////////////////////////////////////////////////////
package com.google.pubsub.kafka;

import com.google.api.client.util.Maps;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.protobuf.ByteString;
import com.google.pubsub.v1.PublishRequest;
import com.google.pubsub.v1.PublishRequest.Builder;
import com.google.pubsub.v1.PublishResponse;
import com.google.pubsub.v1.PubsubMessage;

import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.errors.DataException;
import org.apache.kafka.connect.sink.SinkRecord;
import org.apache.kafka.connect.sink.SinkTask;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

/**
 * CloudPubSubSinkTask publishes records to a Google Cloud Pub/Sub topic.
 */
public class CloudPubSubSinkTask extends SinkTask {
  private static final String SCHEMA_NAME = ByteString.class.getName();
  private static final Logger log = LoggerFactory.getLogger(CloudPubSubSinkTask.class);

  private static final int MAX_MESSAGES_PER_REQUEST = 1000;
  private static final String KEY_ATTRIBUTE = "key";
  private static final String PARTITION_ATTRIBUTE = "partition";
  private static final String TOPIC_FORMAT = "projects/%s/topics/%s";

  private String cpsTopic;
  private int minBatchSize;
  private Map<Integer, List<ListenableFuture<PublishResponse>>> outstandingPublishes =
      Maps.newHashMap();
  private Map<Integer, List<PubsubMessage>> unpublishedMessages = Maps.newHashMap();
  private CloudPubSubPublisher publisher;

  public CloudPubSubSinkTask() {}

  @Override
  public String version() {
    return new CloudPubSubSinkConnector().version();
  }

  @Override
  public void start(Map<String, String> props) {
    this.cpsTopic =
        String.format(
            TOPIC_FORMAT,
            props.get(CloudPubSubSinkConnector.CPS_PROJECT_CONFIG),
            props.get(CloudPubSubSinkConnector.CPS_TOPIC_CONFIG));
    this.minBatchSize = Integer.parseInt(props.get(CloudPubSubSinkConnector.CPS_MIN_BATCH_SIZE));
    log.info("Start connector task for topic " + cpsTopic + " min batch size = " + minBatchSize);
    this.publisher = new CloudPubSubRoundRobinPublisher(10);
  }

  @Override
  public void put(Collection<SinkRecord> sinkRecords) {
    log.debug("Received " + sinkRecords.size() + " messages to send to CPS.");
    for (SinkRecord record : sinkRecords) {
      if (record.valueSchema().type() != Schema.Type.BYTES ||
          !record.valueSchema().name().equals(SCHEMA_NAME)) {
        throw new DataException("Unexpected record of type " + record.valueSchema());
      }
      log.trace("Received record: " + record.toString());
      final Map<String, String> attributes = Maps.newHashMap();
      if (record.key() != null) {
        attributes.put(KEY_ATTRIBUTE, record.key().toString());
      }
      attributes.put(PARTITION_ATTRIBUTE, record.kafkaPartition().toString());
      PubsubMessage message =
          PubsubMessage.newBuilder()
              .setData((ByteString)record.value())
              .putAllAttributes(attributes)
              .build();

      List<PubsubMessage> messagesForPartition = unpublishedMessages.get(record.kafkaPartition());
      if (messagesForPartition == null) {
        messagesForPartition = new ArrayList();
        unpublishedMessages.put(record.kafkaPartition(), messagesForPartition);
      }
      messagesForPartition.add(message);

      if (messagesForPartition.size() >= minBatchSize) {
        publishMessagesForPartition(record.kafkaPartition(), messagesForPartition);
        unpublishedMessages.remove(record.kafkaPartition());
      }
    }
  }

  @Override
  public void flush(Map<TopicPartition, OffsetAndMetadata> partitionOffsets) {
    for (Map.Entry<Integer, List<PubsubMessage>> messagesForPartition :
        unpublishedMessages.entrySet()) {
      publishMessagesForPartition(messagesForPartition.getKey(), messagesForPartition.getValue());
    }
    unpublishedMessages.clear();

    for (Map.Entry<TopicPartition, OffsetAndMetadata> partitionOffset :
        partitionOffsets.entrySet()) {
      log.debug("Received flush for partition " + partitionOffset.getKey().toString());
      List<ListenableFuture<PublishResponse>> outstandingPublishesForPartition =
          outstandingPublishes.get(partitionOffset.getKey().partition());
      if (outstandingPublishesForPartition == null) {
        continue;
      }

      try {
        for (ListenableFuture<PublishResponse> publishRequest : outstandingPublishesForPartition) {
          PublishResponse response = publishRequest.get();
        }
        outstandingPublishes.remove(partitionOffset.getKey().partition());
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  @Override
  public void stop() {}

  private void publishMessagesForPartition(Integer partition, List<PubsubMessage> messages) {
    List<ListenableFuture<PublishResponse>> outstandingPublishesForPartition =
        outstandingPublishes.get(partition);
    if (outstandingPublishesForPartition == null) {
      outstandingPublishesForPartition = new ArrayList();
      outstandingPublishes.put(partition, outstandingPublishesForPartition);
    }

    int startIndex = 0;
    int endIndex = Math.min(MAX_MESSAGES_PER_REQUEST, messages.size());

    while (startIndex < messages.size()) {
      PublishRequest request =
          PublishRequest.newBuilder()
              .setTopic(cpsTopic)
              .addAllMessages(messages.subList(startIndex, endIndex))
              .build();
      // log.info("Publishing: " + (endIndex - startIndex) + " messages");
      outstandingPublishesForPartition.add(publisher.publish(request));
      startIndex = endIndex;
      endIndex = Math.min(endIndex + MAX_MESSAGES_PER_REQUEST, messages.size());
    }
  }
}
