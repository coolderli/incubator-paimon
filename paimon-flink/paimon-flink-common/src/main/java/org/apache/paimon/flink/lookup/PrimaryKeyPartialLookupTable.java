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

package org.apache.paimon.flink.lookup;

import org.apache.paimon.annotation.VisibleForTesting;
import org.apache.paimon.data.BinaryRow;
import org.apache.paimon.data.InternalRow;
import org.apache.paimon.disk.IOManagerImpl;
import org.apache.paimon.flink.query.RemoteTableQuery;
import org.apache.paimon.io.DataFileMeta;
import org.apache.paimon.table.BucketMode;
import org.apache.paimon.table.FileStoreTable;
import org.apache.paimon.table.query.LocalTableQuery;
import org.apache.paimon.table.source.DataSplit;
import org.apache.paimon.table.source.Split;
import org.apache.paimon.table.source.StreamTableScan;
import org.apache.paimon.utils.ProjectedRow;
import org.apache.paimon.utils.Projection;

import javax.annotation.Nullable;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.apache.paimon.CoreOptions.SCAN_BOUNDED_WATERMARK;
import static org.apache.paimon.CoreOptions.STREAM_SCAN_MODE;
import static org.apache.paimon.CoreOptions.StreamScanMode.FILE_MONITOR;

/** Lookup table for primary key which supports to read the LSM tree directly. */
public class PrimaryKeyPartialLookupTable implements LookupTable {

    private final QueryExecutor queryExecutor;
    private final FixedBucketFromPkExtractor extractor;
    @Nullable private final ProjectedRow keyRearrange;

    private PrimaryKeyPartialLookupTable(
            QueryExecutor queryExecutor, FileStoreTable table, List<String> joinKey) {
        this.queryExecutor = queryExecutor;
        if (table.partitionKeys().size() > 0) {
            throw new UnsupportedOperationException(
                    "The partitioned table are not supported in partial cache mode.");
        }

        if (table.bucketMode() != BucketMode.FIXED) {
            throw new UnsupportedOperationException(
                    "Unsupported mode for partial lookup: " + table.bucketMode());
        }

        this.extractor = new FixedBucketFromPkExtractor(table.schema());

        ProjectedRow keyRearrange = null;
        if (!table.primaryKeys().equals(joinKey)) {
            keyRearrange =
                    ProjectedRow.from(
                            table.primaryKeys().stream()
                                    .map(joinKey::indexOf)
                                    .mapToInt(value -> value)
                                    .toArray());
        }
        this.keyRearrange = keyRearrange;
    }

    @VisibleForTesting
    QueryExecutor queryExecutor() {
        return queryExecutor;
    }

    @Override
    public void open() throws Exception {
        refresh();
    }

    @Override
    public List<InternalRow> get(InternalRow key) throws IOException {
        if (keyRearrange != null) {
            key = keyRearrange.replaceRow(key);
        }
        extractor.setRecord(key);
        int bucket = extractor.bucket();
        BinaryRow partition = extractor.partition();

        InternalRow kv = queryExecutor.lookup(partition, bucket, key);
        if (kv == null) {
            return Collections.emptyList();
        } else {
            return Collections.singletonList(kv);
        }
    }

    @Override
    public void refresh() {
        queryExecutor.refresh();
    }

    @Override
    public void close() throws IOException {
        queryExecutor.close();
    }

    public static PrimaryKeyPartialLookupTable createLocalTable(
            FileStoreTable table, int[] projection, File tempPath, List<String> joinKey) {
        LocalQueryExecutor queryExecutor = new LocalQueryExecutor(table, projection, tempPath);
        return new PrimaryKeyPartialLookupTable(queryExecutor, table, joinKey);
    }

    public static PrimaryKeyPartialLookupTable createRemoteTable(
            FileStoreTable table, int[] projection, List<String> joinKey) {
        RemoveQueryExecutor queryExecutor = new RemoveQueryExecutor(table, projection);
        return new PrimaryKeyPartialLookupTable(queryExecutor, table, joinKey);
    }

    interface QueryExecutor extends Closeable {

        InternalRow lookup(BinaryRow partition, int bucket, InternalRow key) throws IOException;

        void refresh();
    }

    static class LocalQueryExecutor implements QueryExecutor {

        private final LocalTableQuery tableQuery;
        private final StreamTableScan scan;

        private LocalQueryExecutor(FileStoreTable table, int[] projection, File tempPath) {
            this.tableQuery =
                    table.newLocalTableQuery()
                            .withValueProjection(Projection.of(projection).toNestedIndexes())
                            .withIOManager(new IOManagerImpl(tempPath.toString()));

            Map<String, String> dynamicOptions = new HashMap<>();
            dynamicOptions.put(STREAM_SCAN_MODE.key(), FILE_MONITOR.getValue());
            dynamicOptions.put(SCAN_BOUNDED_WATERMARK.key(), null);
            this.scan = table.copy(dynamicOptions).newReadBuilder().newStreamScan();
        }

        @Override
        public InternalRow lookup(BinaryRow partition, int bucket, InternalRow key)
                throws IOException {
            return tableQuery.lookup(partition, bucket, key);
        }

        @Override
        public void refresh() {
            while (true) {
                List<Split> splits = scan.plan().splits();
                if (splits.isEmpty()) {
                    return;
                }

                for (Split split : splits) {
                    if (!(split instanceof DataSplit)) {
                        throw new IllegalArgumentException(
                                "Unsupported split: " + split.getClass());
                    }
                    BinaryRow partition = ((DataSplit) split).partition();
                    int bucket = ((DataSplit) split).bucket();
                    List<DataFileMeta> before = ((DataSplit) split).beforeFiles();
                    List<DataFileMeta> after = ((DataSplit) split).dataFiles();

                    tableQuery.refreshFiles(partition, bucket, before, after);
                }
            }
        }

        @Override
        public void close() throws IOException {
            tableQuery.close();
        }
    }

    static class RemoveQueryExecutor implements QueryExecutor {

        private final RemoteTableQuery tableQuery;

        private RemoveQueryExecutor(FileStoreTable table, int[] projection) {
            this.tableQuery = new RemoteTableQuery(table).withValueProjection(projection);
        }

        @Override
        public InternalRow lookup(BinaryRow partition, int bucket, InternalRow key)
                throws IOException {
            return tableQuery.lookup(partition, bucket, key);
        }

        @Override
        public void refresh() {}

        @Override
        public void close() throws IOException {
            tableQuery.close();
        }
    }
}
