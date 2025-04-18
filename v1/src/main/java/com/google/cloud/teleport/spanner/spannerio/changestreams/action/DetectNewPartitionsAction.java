/*
 * Copyright (C) 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.cloud.teleport.spanner.spannerio.changestreams.action;

import com.google.cloud.Timestamp;
import com.google.cloud.spanner.ResultSet;
import com.google.cloud.teleport.spanner.spannerio.changestreams.ChangeStreamMetrics;
import com.google.cloud.teleport.spanner.spannerio.changestreams.cache.WatermarkCache;
import com.google.cloud.teleport.spanner.spannerio.changestreams.dao.PartitionMetadataDao;
import com.google.cloud.teleport.spanner.spannerio.changestreams.mapper.PartitionMetadataMapper;
import com.google.cloud.teleport.spanner.spannerio.changestreams.model.PartitionMetadata;
import com.google.cloud.teleport.spanner.spannerio.changestreams.model.PartitionMetadata.State;
import com.google.cloud.teleport.spanner.spannerio.changestreams.restriction.DetectNewPartitionsRangeTracker;
import com.google.cloud.teleport.spanner.spannerio.changestreams.restriction.TimestampRange;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.apache.beam.sdk.transforms.DoFn.OutputReceiver;
import org.apache.beam.sdk.transforms.DoFn.ProcessContinuation;
import org.apache.beam.sdk.transforms.splittabledofn.ManualWatermarkEstimator;
import org.apache.beam.sdk.transforms.splittabledofn.RestrictionTracker;
import org.joda.time.Duration;
import org.joda.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class is responsible for scheduling partitions. It obtains partitions to be scheduled from
 * the partition metadata table. The full algorithm is described in {@link
 * DetectNewPartitionsAction#run(RestrictionTracker, OutputReceiver, ManualWatermarkEstimator)}.
 */
public class DetectNewPartitionsAction {
  private static final Logger LOG = LoggerFactory.getLogger(DetectNewPartitionsAction.class);

  private final PartitionMetadataDao dao;
  private final PartitionMetadataMapper mapper;
  private final WatermarkCache cache;
  private final ChangeStreamMetrics metrics;
  private final Duration resumeDuration;

  /** Constructs an action class for detecting / scheduling new partitions. */
  public DetectNewPartitionsAction(
      PartitionMetadataDao dao,
      PartitionMetadataMapper mapper,
      WatermarkCache cache,
      ChangeStreamMetrics metrics,
      Duration resumeDuration) {
    this.dao = dao;
    this.mapper = mapper;
    this.cache = cache;
    this.metrics = metrics;
    this.resumeDuration = resumeDuration;
  }

  /**
   * Executes the main logic to schedule new partitions. It follows this procedure periodically:
   *
   * <ol>
   *   <li>Fetches the min watermark from all the unfinished partitions in the metadata tables.
   *   <li>If there are no unfinished partitions, this function will stop and not be re-scheduled.
   *   <li>Updates the component's watermark to the min fetched.
   *   <li>Fetches the read timestamp from the restriction.
   *   <li>Fetches all the partitions with a createdAt timestamp > read timestamp.
   *   <li>Groups the partitions by createdAt timestamp.
   *   <li>Process the groups in ascending order of createdAt timestamp (oldest first)
   *   <li>For each group, updates the state to {@link State#SCHEDULED}.
   *   <li>Tries to claim the createdAt timestamp of the group within the restriction.
   *   <li>If it is possible to claim the timestamp, outputs each partition to the next stage. It
   *       then proceeds to process the next batch. When there are no more batches to process,
   *       schedules the function to resume after the configured resume duration.
   *   <li>If it is not possible to claim the timestamp, stops.
   * </ol>
   *
   * @param tracker an instance of {@link DetectNewPartitionsRangeTracker}
   * @param receiver a {@link PartitionMetadata} {@link OutputReceiver}
   * @param watermarkEstimator a {@link ManualWatermarkEstimator} of {@link Instant}
   * @return a {@link ProcessContinuation#stop()} if there are no more partitions to process or
   *     {@link ProcessContinuation#resume()} to re-schedule the function after the configured
   *     interval.
   */
  public ProcessContinuation run(
      RestrictionTracker<TimestampRange, Timestamp> tracker,
      OutputReceiver<PartitionMetadata> receiver,
      ManualWatermarkEstimator<Instant> watermarkEstimator) {

    final Timestamp readTimestamp = tracker.currentRestriction().getFrom();
    // Updates the current watermark as the min of the watermarks from all existing partitions
    final Timestamp minWatermark = cache.getUnfinishedMinWatermark();

    if (minWatermark != null) {
      return processPartitions(tracker, receiver, watermarkEstimator, minWatermark, readTimestamp);
    } else {
      return terminate(tracker);
    }
  }

  private ProcessContinuation processPartitions(
      RestrictionTracker<TimestampRange, Timestamp> tracker,
      OutputReceiver<PartitionMetadata> receiver,
      ManualWatermarkEstimator<Instant> watermarkEstimator,
      Timestamp minWatermark,
      Timestamp readTimestamp) {
    // Updates watermark to the min watermark found
    watermarkEstimator.setWatermark(new Instant(minWatermark.toSqlTimestamp()));

    final List<PartitionMetadata> partitions = getAllPartitionsCreatedAfter(readTimestamp);
    final TreeMap<Timestamp, List<PartitionMetadata>> batches = batchByCreatedAt(partitions);
    return schedulePartitions(tracker, receiver, minWatermark, batches);
  }

  private List<PartitionMetadata> getAllPartitionsCreatedAfter(Timestamp readTimestamp) {
    final List<PartitionMetadata> partitions = new ArrayList<>();
    try (ResultSet resultSet = dao.getAllPartitionsCreatedAfter(readTimestamp)) {
      while (resultSet.next()) {
        final PartitionMetadata partition = mapper.from(resultSet.getCurrentRowAsStruct());
        partitions.add(partition);
      }
    }
    LOG.info("Found {} to be scheduled (readTimestamp = {})", partitions.size(), readTimestamp);
    return partitions;
  }

  private TreeMap<Timestamp, List<PartitionMetadata>> batchByCreatedAt(
      List<PartitionMetadata> partitions) {
    return partitions.stream()
        .collect(
            Collectors.groupingBy(
                PartitionMetadata::getCreatedAt, TreeMap::new, Collectors.toList()));
  }

  private ProcessContinuation schedulePartitions(
      RestrictionTracker<TimestampRange, Timestamp> tracker,
      OutputReceiver<PartitionMetadata> receiver,
      Timestamp minWatermark,
      TreeMap<Timestamp, List<PartitionMetadata>> batches) {
    List<PartitionMetadata> batchPartitionsDifferentCreatedAt = new ArrayList<>();
    int numTimestampsHandledSofar = 0;
    for (Map.Entry<Timestamp, List<PartitionMetadata>> batch : batches.entrySet()) {
      numTimestampsHandledSofar++;
      final Timestamp batchCreatedAt = batch.getKey();
      final List<PartitionMetadata> batchPartitionsSameCreatedAt = batch.getValue();
      batchPartitionsDifferentCreatedAt.addAll(batchPartitionsSameCreatedAt);
      if (batchPartitionsDifferentCreatedAt.size() >= 200
          || numTimestampsHandledSofar == batches.size()) {
        final Timestamp scheduledAt = updateBatchToScheduled(batchPartitionsDifferentCreatedAt);
        if (!tracker.tryClaim(batchCreatedAt)) {
          return ProcessContinuation.stop();
        }
        outputBatch(receiver, minWatermark, batchPartitionsDifferentCreatedAt, scheduledAt);
        batchPartitionsDifferentCreatedAt = new ArrayList<>();
      }
    }

    return ProcessContinuation.resume().withResumeDelay(resumeDuration);
  }

  private Timestamp updateBatchToScheduled(List<PartitionMetadata> batchPartitions) {
    final List<String> batchPartitionTokens =
        batchPartitions.stream()
            .map(PartitionMetadata::getPartitionToken)
            .collect(Collectors.toList());
    return dao.updateToScheduled(batchPartitionTokens);
  }

  private void outputBatch(
      OutputReceiver<PartitionMetadata> receiver,
      Timestamp minWatermark,
      List<PartitionMetadata> batchPartitions,
      Timestamp scheduledAt) {
    for (PartitionMetadata partition : batchPartitions) {
      final Timestamp createdAt = partition.getCreatedAt();
      final PartitionMetadata updatedPartition =
          partition.toBuilder().setScheduledAt(scheduledAt).build();

      LOG.info(
          "[{}] Outputting partition at {} with start time {} and end time {}",
          updatedPartition.getPartitionToken(),
          updatedPartition.getScheduledAt(),
          updatedPartition.getStartTimestamp(),
          updatedPartition.getEndTimestamp());

      receiver.outputWithTimestamp(partition, new Instant(minWatermark.toSqlTimestamp()));

      metrics.incPartitionRecordCount();
      metrics.updatePartitionCreatedToScheduled(
          new Duration(
              createdAt.toSqlTimestamp().getTime(), scheduledAt.toSqlTimestamp().getTime()));
    }
  }

  private ProcessContinuation terminate(RestrictionTracker<TimestampRange, Timestamp> tracker) {
    // We need to try claim something here, otherwise restriction tracker check done fails
    tracker.tryClaim(tracker.currentRestriction().getTo());
    LOG.info("All partitions have been processed, stopping");
    return ProcessContinuation.stop();
  }
}
