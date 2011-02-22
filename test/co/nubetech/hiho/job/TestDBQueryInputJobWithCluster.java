/**
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


package co.nubetech.hiho.job;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Random;

import org.apache.hadoop.fs.ContentSummary;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapred.Utils;
import org.apache.hadoop.util.ToolRunner;
import org.apache.log4j.Logger;
import org.hsqldb.Server;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import co.nubetech.hiho.common.HihoTestCase;

public class TestDBQueryInputJobWithCluster extends HihoTestCase {

	final static Logger logger = Logger
			.getLogger(co.nubetech.hiho.job.TestDBQueryInputJobWithCluster.class);

	private static final String DB_URL = "jdbc:hsqldb:hsql://localhost/URLAccess";
	private static final String DRIVER_CLASS = "org.hsqldb.jdbcDriver";
	private static Server server;
	private static Connection connection;

	

	@Test
	public void testBasicTableImport() throws Exception {
		DBQueryInputJob job = new DBQueryInputJob();

		String[] args = new String[] {
				"-jdbcDriver",
				"org.hsqldb.jdbcDriver",
				"-jdbcUrl",
				"jdbc:hsqldb:hsql://localhost/URLAccess",
				// "-jdbcUsername", "",
				// "-jdbcPassword", "",
				"-outputPath", getOutputDir().toString(), "-outputStrategy",
				"delimited", "-delimiter", "DELIM", "-numberOfMappers", "2",
				"-inputTableName", "Pageview", "-inputOrderBy", "pageview" };
		int res = ToolRunner.run(createJobConf(), job, args);
		assertEquals(0, res);
		// lets verify the result now
		FileSystem outputFS = getFileSystem();
		Path outputPath = getOutputDir();
		// Path outputPath = new Path("output");
		ContentSummary content = outputFS.getContentSummary(outputPath);
		logger.debug("Content output is  " + content.getHeader(true));

		Path[] outputFiles = FileUtil.stat2Paths(getFileSystem().listStatus(
				getOutputDir(), new Utils.OutputFileUtils.OutputFilesFilter()));
		assertEquals(2, outputFiles.length);
		for (int i = 0; i < outputFiles.length; ++i) {
			InputStream is = getFileSystem().open(outputFiles[i]);
			BufferedReader reader = new BufferedReader(
					new InputStreamReader(is));
			String line = reader.readLine();
			int counter = 0;
			while (line != null) {
				counter++;
				logger.debug(" Output is " + line);
				line = reader.readLine();
			}
			reader.close();
			assertEquals(5, counter);

		}

		/*
		 * for (int i = 0 ; i < content.getFileCount(); ++i) {
		 * 
		 * } //assertTrue(outputFS.exists(outputPath1));
		 */

		/*
		 * FSDataInputStream in = null; in = outputFS.open(outputPath);
		 * 
		 * while (in.readLine() != null) {
		 * logger.debug("Foind files in outputPath " + in.readLine()); }
		 */

	}

	private static void startHsqldbServer() {
		server = new Server();
		server.setDatabasePath(0, System.getProperty("test.build.data", "/tmp")
				+ "/URLAccess");
		server.setDatabaseName(0, "URLAccess");
		server.start();
	}

	private static void createConnection(String driverClassName, String url)
			throws Exception {

		Class.forName(driverClassName);
		connection = DriverManager.getConnection(url);
		connection.setAutoCommit(false);
	}

	@AfterClass
	public static void shutdown() {
		try {
			connection.commit();
			connection.close();
		} catch (Throwable ex) {
			logger.warn("Exception occurred while closing connection :" + ex);
		} finally {
			try {
				if (server != null) {
					server.shutdown();
				}
			} catch (Throwable ex) {
				logger.warn("Exception occurred while shutting down HSQLDB :"
						+ ex);
			}
		}
	}

	@BeforeClass
	public static void initialize() throws Exception {
		startHsqldbServer();
		createConnection(DRIVER_CLASS, DB_URL);
		dropTables();
		createTables();
		populateAccess();
		verify();
	}

	private static void dropTables() {
		String dropAccess = "DROP TABLE Access";
		String dropPageview = "DROP TABLE Pageview";
		Statement st = null;
		try {
			st = connection.createStatement();
			st.executeUpdate(dropAccess);
			st.executeUpdate(dropPageview);
			connection.commit();
			st.close();
		} catch (SQLException ex) {
			try {
				if (st != null) {
					st.close();
				}
			} catch (Exception e) {
			}
		}
	}

	private static void createTables() throws SQLException {

		String createAccess = "CREATE TABLE "
				+ "Access(url      VARCHAR(100) NOT NULL,"
				+ " referrer VARCHAR(100)," + " time     BIGINT NOT NULL, "
				+ " PRIMARY KEY (url, time))";

		String createPageview = "CREATE TABLE "
				+ "Pageview(url      VARCHAR(100) NOT NULL,"
				+ " pageview     BIGINT NOT NULL, " + " PRIMARY KEY (url))";

		Statement st = connection.createStatement();
		try {
			st.executeUpdate(createAccess);
			st.executeUpdate(createPageview);
			connection.commit();
		} finally {
			st.close();
		}
	}

	/**
	 * Populates the Access table with generated records.
	 */
	private static void populateAccess() throws SQLException {

		PreparedStatement statement = null;
		try {
			statement = connection
					.prepareStatement("INSERT INTO Access(url, referrer, time)"
							+ " VALUES (?, ?, ?)");

			Random random = new Random();

			int time = random.nextInt(50) + 50;

			final int PROBABILITY_PRECISION = 100; // 1 / 100
			final int NEW_PAGE_PROBABILITY = 15; // 15 / 100

			// Pages in the site :
			String[] pages = { "/a", "/b", "/c", "/d", "/e", "/f", "/g", "/h",
					"/i", "/j" };
			// linkMatrix[i] is the array of pages(indexes) that page_i links
			// to.
			int[][] linkMatrix = { { 1, 5, 7 }, { 0, 7, 4, 6, },
					{ 0, 1, 7, 8 }, { 0, 2, 4, 6, 7, 9 }, { 0, 1 },
					{ 0, 3, 5, 9 }, { 0 }, { 0, 1, 3 }, { 0, 2, 6 },
					{ 0, 2, 6 } };

			// a mini model of user browsing a la pagerank
			int currentPage = random.nextInt(pages.length);
			String referrer = null;

			for (int i = 0; i < time; i++) {

				statement.setString(1, pages[currentPage]);
				statement.setString(2, referrer);
				statement.setLong(3, i);
				statement.execute();

				int action = random.nextInt(PROBABILITY_PRECISION);

				// go to a new page with probability
				// NEW_PAGE_PROBABILITY / PROBABILITY_PRECISION
				if (action < NEW_PAGE_PROBABILITY) {
					currentPage = random.nextInt(pages.length); // a random page
					referrer = null;
				} else {
					referrer = pages[currentPage];
					action = random.nextInt(linkMatrix[currentPage].length);
					currentPage = linkMatrix[currentPage][action];
				}
			}

			PreparedStatement statement1 = connection
					.prepareStatement("INSERT INTO Pageview(url, pageview)"
							+ " VALUES (?, ?)");

			int i = 1;
			for (String page : pages) {
				statement1.setString(1, page);
				statement1.setInt(2, 1000 * i);
				i++;
				statement1.execute();
			}

			connection.commit();

		} catch (SQLException ex) {
			connection.rollback();
			throw ex;
		} finally {
			if (statement != null) {
				statement.close();
			}
		}
	}

	/** Verifies the results are correct */
	private static boolean verify() throws SQLException {
		// check total num pageview
		String countAccessQuery = "SELECT COUNT(*) FROM Access";
		String sumPageviewQuery = "SELECT SUM(pageview) FROM Pageview";
		String totalPageviewQuery = "SELECT COUNT(*) FROM Pageview";
		Statement st = null;
		ResultSet rs = null;
		try {
			st = connection.createStatement();
			rs = st.executeQuery(countAccessQuery);
			rs.next();
			long totalPageview = rs.getLong(1);

			rs = st.executeQuery(sumPageviewQuery);
			rs.next();
			long sumPageview = rs.getLong(1);

			rs = st.executeQuery(totalPageviewQuery);
			rs.next();
			long countPageview = rs.getLong(1);

			logger.info("totalAccessview=" + totalPageview);
			logger.info("sumPageview=" + sumPageview);
			logger.info("totalPageview=" + countPageview);

			return totalPageview == sumPageview && totalPageview != 0;
		} finally {
			if (st != null)
				st.close();
			
			if (rs != null)
				rs.close();
		}
	}

}
