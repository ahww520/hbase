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
package org.apache.hadoop.hbase.client;

import static org.apache.hadoop.hbase.HBaseTestingUtil.fam1;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.ExtendedCellScannable;
import org.apache.hadoop.hbase.ExtendedCellScanner;
import org.apache.hadoop.hbase.HBaseClassTestRule;
import org.apache.hadoop.hbase.HBaseTestingUtil;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Scan.ReadType;
import org.apache.hadoop.hbase.coprocessor.CoprocessorHost;
import org.apache.hadoop.hbase.coprocessor.ProtobufCoprocessorService;
import org.apache.hadoop.hbase.ipc.DelegatingHBaseRpcController;
import org.apache.hadoop.hbase.ipc.HBaseRpcController;
import org.apache.hadoop.hbase.ipc.RpcControllerFactory;
import org.apache.hadoop.hbase.testclassification.ClientTests;
import org.apache.hadoop.hbase.testclassification.MediumTests;
import org.apache.hadoop.hbase.util.Bytes;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.TestName;

import org.apache.hbase.thirdparty.com.google.common.collect.ConcurrentHashMultiset;
import org.apache.hbase.thirdparty.com.google.common.collect.Lists;
import org.apache.hbase.thirdparty.com.google.common.collect.Multiset;

@Category({ MediumTests.class, ClientTests.class })
public class TestRpcControllerFactory {

  @ClassRule
  public static final HBaseClassTestRule CLASS_RULE =
    HBaseClassTestRule.forClass(TestRpcControllerFactory.class);

  public static class StaticRpcControllerFactory extends RpcControllerFactory {

    public StaticRpcControllerFactory(Configuration conf) {
      super(conf);
    }

    @Override
    public HBaseRpcController newController() {
      return new CountingRpcController(super.newController());
    }

    @Override
    public HBaseRpcController newController(RegionInfo regionInfo,
      ExtendedCellScanner cellScanner) {
      return new CountingRpcController(super.newController(regionInfo, cellScanner));
    }

    @Override
    public HBaseRpcController newController(RegionInfo regionInfo,
      List<ExtendedCellScannable> cellIterables) {
      return new CountingRpcController(super.newController(regionInfo, cellIterables));
    }
  }

  public static class CountingRpcController extends DelegatingHBaseRpcController {

    private static Multiset<Integer> GROUPED_PRIORITY = ConcurrentHashMultiset.create();
    private static AtomicInteger INT_PRIORITY = new AtomicInteger();

    public CountingRpcController(HBaseRpcController delegate) {
      super(delegate);
    }

    @Override
    public void setPriority(int priority) {
      INT_PRIORITY.incrementAndGet();
      GROUPED_PRIORITY.add(priority);
    }
  }

  private static final HBaseTestingUtil UTIL = new HBaseTestingUtil();

  @Rule
  public TestName name = new TestName();

  @BeforeClass
  public static void setUp() throws Exception {
    // load an endpoint so we have an endpoint to test - it doesn't matter which one, but
    // this is already in tests, so we can just use it.
    Configuration conf = UTIL.getConfiguration();
    conf.set(CoprocessorHost.REGION_COPROCESSOR_CONF_KEY,
      ProtobufCoprocessorService.class.getName());

    UTIL.startMiniCluster();
  }

  @AfterClass
  public static void tearDown() throws Exception {
    UTIL.shutdownMiniCluster();
  }

  /**
   * check some of the methods and make sure we are incrementing each time. Its a bit tediuous to
   * cover all methods here and really is a bit brittle since we can always add new methods but
   * won't be sure to add them here. So we just can cover the major ones.
   * @throws Exception on failure
   */
  @Test
  public void testCountController() throws Exception {
    Configuration conf = new Configuration(UTIL.getConfiguration());
    // setup our custom controller
    conf.set(RpcControllerFactory.CUSTOM_CONTROLLER_CONF_KEY,
      StaticRpcControllerFactory.class.getName());

    final TableName tableName = TableName.valueOf(name.getMethodName());
    UTIL.createTable(tableName, fam1).close();

    // change one of the connection properties so we get a new Connection with our configuration
    conf.setInt(HConstants.HBASE_RPC_TIMEOUT_KEY, HConstants.DEFAULT_HBASE_RPC_TIMEOUT + 1);

    try (Connection connection = ConnectionFactory.createConnection(conf);
      Table table = connection.getTable(tableName)) {
      byte[] row = Bytes.toBytes("row");
      Put p = new Put(row);
      p.addColumn(fam1, fam1, Bytes.toBytes("val0"));
      table.put(p);

      Integer counter = 1;
      counter = verifyCount(counter);

      Delete d = new Delete(row);
      d.addColumn(fam1, fam1);
      table.delete(d);
      counter = verifyCount(counter);

      Put p2 = new Put(row);
      p2.addColumn(fam1, Bytes.toBytes("qual"), Bytes.toBytes("val1"));
      table.batch(Lists.newArrayList(p, p2), null);
      // this only goes to a single server, so we don't need to change the count here
      counter = verifyCount(counter);

      Append append = new Append(row);
      append.addColumn(fam1, fam1, Bytes.toBytes("val2"));
      table.append(append);
      counter = verifyCount(counter);

      // and check the major lookup calls as well
      Get g = new Get(row);
      table.get(g);
      counter = verifyCount(counter);

      ResultScanner scan = table.getScanner(fam1);
      scan.next();
      scan.close();
      counter = verifyCount(counter + 1);

      Get g2 = new Get(row);
      table.get(Lists.newArrayList(g, g2));
      // same server, so same as above for not changing count
      counter = verifyCount(counter);

      // make sure all the scanner types are covered
      Scan scanInfo = new Scan().withStartRow(row);
      // regular small
      scanInfo.setReadType(ReadType.PREAD);
      counter = doScan(table, scanInfo, counter);

      // reversed, small
      scanInfo.setReversed(true);
      counter = doScan(table, scanInfo, counter);

      // reversed, regular
      scanInfo.setReadType(ReadType.STREAM);
      doScan(table, scanInfo, counter + 1);

      // make sure we have no priority count
      verifyPriorityGroupCount(HConstants.ADMIN_QOS, 0);
      // lets set a custom priority on a get
      Get get = new Get(row);
      get.setPriority(HConstants.ADMIN_QOS);
      table.get(get);
      // we will reset the controller for setting the call timeout so it will lead to an extra
      // setPriority
      verifyPriorityGroupCount(HConstants.ADMIN_QOS, 2);
    }
  }

  int doScan(Table table, Scan scan, int expectedCount) throws IOException {
    try (ResultScanner results = table.getScanner(scan)) {
      results.next();
    }
    return verifyCount(expectedCount);
  }

  int verifyCount(Integer counter) {
    assertTrue(CountingRpcController.INT_PRIORITY.get() >= counter);
    return CountingRpcController.GROUPED_PRIORITY.count(HConstants.NORMAL_QOS) + 1;
  }

  void verifyPriorityGroupCount(int priorityLevel, int count) {
    assertEquals(count, CountingRpcController.GROUPED_PRIORITY.count(priorityLevel));
  }

  @Test
  public void testFallbackToDefaultRpcControllerFactory() {
    Configuration conf = new Configuration(UTIL.getConfiguration());
    conf.set(RpcControllerFactory.CUSTOM_CONTROLLER_CONF_KEY, "foo.bar.Baz");

    // Should not fail
    RpcControllerFactory factory = RpcControllerFactory.instantiate(conf);
    assertNotNull(factory);
    assertEquals(factory.getClass(), RpcControllerFactory.class);
  }
}
