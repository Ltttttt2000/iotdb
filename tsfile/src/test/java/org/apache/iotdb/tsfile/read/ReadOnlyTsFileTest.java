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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iotdb.tsfile.read;

import org.apache.iotdb.tsfile.common.conf.TSFileConfig;
import org.apache.iotdb.tsfile.common.conf.TSFileDescriptor;
import org.apache.iotdb.tsfile.exception.write.WriteProcessException;
import org.apache.iotdb.tsfile.file.metadata.enums.CompressionType;
import org.apache.iotdb.tsfile.file.metadata.enums.TSDataType;
import org.apache.iotdb.tsfile.file.metadata.enums.TSEncoding;
import org.apache.iotdb.tsfile.read.common.Path;
import org.apache.iotdb.tsfile.read.common.RowRecord;
import org.apache.iotdb.tsfile.read.expression.IExpression;
import org.apache.iotdb.tsfile.read.expression.QueryExpression;
import org.apache.iotdb.tsfile.read.expression.impl.BinaryExpression;
import org.apache.iotdb.tsfile.read.expression.impl.GlobalTimeExpression;
import org.apache.iotdb.tsfile.read.expression.impl.SingleSeriesExpression;
import org.apache.iotdb.tsfile.read.filter.TimeFilter;
import org.apache.iotdb.tsfile.read.filter.ValueFilter;
import org.apache.iotdb.tsfile.read.filter.basic.Filter;
import org.apache.iotdb.tsfile.read.filter.factory.FilterFactory;
import org.apache.iotdb.tsfile.read.query.dataset.QueryDataSet;
import org.apache.iotdb.tsfile.utils.Binary;
import org.apache.iotdb.tsfile.utils.TsFileGeneratorForTest;
import org.apache.iotdb.tsfile.write.TsFileWriter;
import org.apache.iotdb.tsfile.write.record.TSRecord;
import org.apache.iotdb.tsfile.write.record.datapoint.IntDataPoint;
import org.apache.iotdb.tsfile.write.schema.MeasurementSchema;
import org.apache.iotdb.tsfile.write.schema.Schema;

import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;

public class ReadOnlyTsFileTest {

  private static final String FILE_PATH = TsFileGeneratorForTest.outputDataFile;
  private TsFileSequenceReader fileReader;
  private ReadOnlyTsFile tsFile;

  @Test
  public void multiPagesTest() throws IOException, WriteProcessException {
    final String filePath = "target/multiPages.tsfile";

    TSFileConfig tsFileConfig = TSFileDescriptor.getInstance().getConfig();
    // make multi pages in one group
    tsFileConfig.setMaxNumberOfPointsInPage(100);
    tsFileConfig.setGroupSizeInByte(100 * 1024 * 1024);
    File file = new File(filePath);
    TsFileWriter tsFileWriter = new TsFileWriter(file, new Schema(), tsFileConfig);

    Path path = new Path("t", "id");
    tsFileWriter.registerTimeseries(
        path, new MeasurementSchema("id", TSDataType.INT32, TSEncoding.PLAIN, CompressionType.LZ4));

    for (int i = 0; i < 11000000; i++) {
      TSRecord t = new TSRecord(i, "t");
      if (i % 100 == 0) {
        // Add a large max_value to the page statistics,
        // and get a very large number of invalid pages when the query is executed
        t.addTuple(new IntDataPoint("id", 9000001));
      } else {
        t.addTuple(new IntDataPoint("id", i));
      }
      tsFileWriter.write(t);
    }
    // make same value to filter
    TSRecord t = new TSRecord(101011000000L, "t");
    t.addTuple(new IntDataPoint("id", 8000001));
    tsFileWriter.write(t);
    tsFileWriter.flushAllChunkGroups();
    tsFileWriter.close();

    ReadOnlyTsFile readOnlyTsFile = new ReadOnlyTsFile(new TsFileSequenceReader(filePath));

    SingleSeriesExpression filter = new SingleSeriesExpression(path, ValueFilter.eq(8000001));
    QueryExpression queryExpression = QueryExpression.create(Arrays.asList(path), filter);
    QueryDataSet query = readOnlyTsFile.query(queryExpression);

    int i = 0;
    Assert.assertTrue(query.hasNext());
    while (query.hasNext()) {
      RowRecord next = query.next();
      if (i == 0) {
        Assert.assertEquals(next.getTimestamp(), 8000001);
        Assert.assertEquals(next.getFields().get(0).getIntV(), 8000001);
        i++;
      } else {
        Assert.assertEquals(next.getTimestamp(), 101011000000L);
        Assert.assertEquals(next.getFields().get(0).getIntV(), 8000001);
      }
    }

    readOnlyTsFile.close();
    file.delete();
  }

  @Test
  public void test1() throws IOException {
    TSFileDescriptor.getInstance().getConfig().setTimeEncoder("TS_2DIFF");
    int rowCount = 1000;
    TsFileGeneratorForTest.generateFile(rowCount, 16 * 1024 * 1024, 10000);
    fileReader = new TsFileSequenceReader(FILE_PATH);
    tsFile = new ReadOnlyTsFile(fileReader);
    queryTest(rowCount);
    tsFile.close();
    TsFileGeneratorForTest.after();
  }

  private void queryTest(int rowCount) throws IOException {
    Filter filter = TimeFilter.lt(1480562618100L);
    Filter filter2 = ValueFilter.gt(new Binary("dog"));
    Filter filter3 =
        FilterFactory.and(TimeFilter.gtEq(1480562618000L), TimeFilter.ltEq(1480562618100L));

    IExpression IExpression =
        BinaryExpression.or(
            BinaryExpression.and(
                new SingleSeriesExpression(new Path("d1", "s1"), filter),
                new SingleSeriesExpression(new Path("d1", "s4"), filter2)),
            new GlobalTimeExpression(filter3));

    QueryExpression queryExpression =
        QueryExpression.create()
            .addSelectedPath(new Path("d1", "s1"))
            .addSelectedPath(new Path("d1", "s4"))
            .setExpression(IExpression);
    QueryDataSet queryDataSet = tsFile.query(queryExpression);
    long aimedTimestamp = 1480562618000L;
    while (queryDataSet.hasNext()) {
      // System.out.println("find next!");
      RowRecord rowRecord = queryDataSet.next();
      // System.out.println("result datum: "+rowRecord.getTimestamp()+","
      // +rowRecord.getFields());
      Assert.assertEquals(aimedTimestamp, rowRecord.getTimestamp());
      aimedTimestamp++;
    }

    queryExpression =
        QueryExpression.create()
            .addSelectedPath(new Path("d1", "s1"))
            .addSelectedPath(new Path("d1", "s4"));
    queryDataSet = tsFile.query(queryExpression);
    aimedTimestamp = 1480562618000L;
    int count = 0;
    while (queryDataSet.hasNext()) {
      RowRecord rowRecord = queryDataSet.next();
      Assert.assertEquals(aimedTimestamp, rowRecord.getTimestamp());
      aimedTimestamp++;
      count++;
    }
    Assert.assertEquals(rowCount, count);

    queryExpression =
        QueryExpression.create()
            .addSelectedPath(new Path("d1", "s1"))
            .addSelectedPath(new Path("d1", "s4"))
            .setExpression(new GlobalTimeExpression(filter3));
    queryDataSet = tsFile.query(queryExpression);
    aimedTimestamp = 1480562618000L;
    count = 0;
    while (queryDataSet.hasNext()) {
      RowRecord rowRecord = queryDataSet.next();
      Assert.assertEquals(aimedTimestamp, rowRecord.getTimestamp());
      aimedTimestamp++;
      count++;
    }
    Assert.assertEquals(101, count);
  }

  @Test
  public void test2() throws Exception {
    int minRowCount = 1000, maxRowCount = 100000;
    TSFileDescriptor.getInstance().getConfig().setTimeEncoder("TS_2DIFF");
    TsFileGeneratorForTest.generateFile(minRowCount, maxRowCount, 16 * 1024 * 1024, 10000);
    fileReader = new TsFileSequenceReader(FILE_PATH);
    tsFile = new ReadOnlyTsFile(fileReader);
    queryTest2();
    queryNonExistPathTest();
    tsFile.close();
    TsFileGeneratorForTest.after();
  }

  void queryTest2() throws IOException {
    ArrayList<Path> paths = new ArrayList<>();
    paths.add(new Path("d1", "s6"));
    paths.add(new Path("d2", "s1"));

    IExpression expression = new GlobalTimeExpression(TimeFilter.gt(1480562664760L));

    QueryExpression queryExpression = QueryExpression.create(paths, expression);

    QueryDataSet queryDataSet = tsFile.query(queryExpression);

    int cnt = 0;
    while (queryDataSet.hasNext()) {
      RowRecord r = queryDataSet.next();
      // System.out.println(r);
      cnt++;
    }
    Assert.assertEquals(10647, cnt);
  }

  void queryNonExistPathTest() throws Exception {
    ArrayList<Path> paths = new ArrayList<>();
    paths.add(new Path("dr", "s1"));
    paths.add(new Path("d2", "s1"));
    IExpression expression = new GlobalTimeExpression(TimeFilter.gt(1480562664760L));
    QueryExpression queryExpression = QueryExpression.create(paths, expression);
    try {
      QueryDataSet queryDataSet = tsFile.query(queryExpression);
    } catch (Exception e) {
      throw new Exception(e);
      // fail();
    }
  }
}
