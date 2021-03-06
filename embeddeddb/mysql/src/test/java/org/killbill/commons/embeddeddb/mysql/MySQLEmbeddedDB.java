/*
 * Copyright 2010-2013 Ning, Inc.
 * Copyright 2014-2015 Groupon, Inc
 * Copyright 2014-2015 The Billing Project, LLC
 *
 * The Billing Project licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.killbill.commons.embeddeddb.mysql;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sql.DataSource;

import org.killbill.commons.embeddeddb.EmbeddedDB;
import org.mariadb.jdbc.MariaDbDataSource;
import org.slf4j.event.Level;

import com.mysql.management.HackedMysqldResource;
import com.mysql.management.MysqldResourceI;

public class MySQLEmbeddedDB extends EmbeddedDB {

    protected final AtomicBoolean started = new AtomicBoolean(false);

    protected final int port;

    private File dbDir;
    private File dataDir;
    private HackedMysqldResource mysqldResource;

    public MySQLEmbeddedDB() {
        // Avoid dashes - MySQL doesn't like them
        this("database" + UUID.randomUUID().toString().substring(0, 8),
             "user" + UUID.randomUUID().toString().substring(0, 8),
             "pass" + UUID.randomUUID().toString().substring(0, 8));
    }

    public MySQLEmbeddedDB(final String databaseName, final String username, final String password) {
        super(databaseName, username, password, null);
        this.port = getPort();
        this.jdbcConnectionString = "jdbc:mysql://localhost:" + port + "/" + databaseName + "?createDatabaseIfNotExist=true&allowMultiQueries=true";
    }


    @Override
    public DBEngine getDBEngine() {
        return DBEngine.MYSQL;
    }

    @Override
    public void initialize() throws IOException {
    }

    @Override
    public void start() throws IOException, SQLException {
        if (started.get()) {
            throw new IOException("MySQL is already running: " + jdbcConnectionString);
        }

        startMysql();

        createDataSource();

        refreshTableNames();
    }

    @Override
    public void refreshTableNames() throws IOException {
        final String query = String.format("select table_name from information_schema.tables where table_schema = '%s' and table_type = 'BASE TABLE';", databaseName);
        try {
            executeQuery(query, new ResultSetJob() {
                @Override
                public void work(final ResultSet resultSet) throws SQLException {
                    allTables.clear();
                    while (resultSet.next()) {
                        allTables.add(resultSet.getString(1));
                    }
                }
            });
        } catch (final SQLException e) {
            throw new IOException(e);
        }
    }

    @Override
    public DataSource getDataSource() throws IOException {
        if (!started.get()) {
            throw new IOException("MySQL is not running");
        }
        return super.getDataSource();
    }

    @Override
    public void stop() throws IOException {
        if (!started.get()) {
            throw new IOException("MySQL is not running");
        }
        super.stop();
        stopMysql();
    }

    @Override
    public String getCmdLineConnectionString() {
        return String.format("mysql -u%s -p%s -P%s -S%s/mysql.sock %s", username, password, port, dataDir, databaseName);
    }

    protected void createDataSource() throws IOException, SQLException {
        if (useConnectionPooling()) {
            dataSource = createHikariDataSource();
        } else {
            final MariaDbDataSource mariaDBDataSource = new MariaDbDataSource();
            try {
                mariaDBDataSource.setUrl(jdbcConnectionString);
            } catch (final SQLException e) {
                throw new IOException(e);
            }
            mariaDBDataSource.setDatabaseName(databaseName);
            mariaDBDataSource.setUser(username);
            mariaDBDataSource.setPassword(password);
            mariaDBDataSource.setPort(port);
            dataSource = mariaDBDataSource;
        }
    }


    private void startMysql() throws IOException {
        dbDir = File.createTempFile("mysqldb", "");
        if (!dbDir.delete()) {
            logger.warn("Unable to delete " + dbDir.getAbsolutePath());
        }
        if (!dbDir.mkdir()) {
            throw new IOException("Unable to create " + dbDir.getAbsolutePath());
        }

        dataDir = File.createTempFile("mysqldata", "");
        if (!dataDir.delete()) {
            logger.warn("Unable to delete " + dataDir.getAbsolutePath());
        }
        if (!dataDir.mkdir()) {
            throw new IOException("Unable to create " + dataDir.getAbsolutePath());
        }

        final PrintStream out = new PrintStream(new LoggingOutputStream(logger, Level.INFO), true);
        final PrintStream err = new PrintStream(new LoggingOutputStream(logger, Level.WARN), true);
        final PrintStream debug = new PrintStream(new LoggingOutputStream(logger, Level.DEBUG), true);
        mysqldResource = new HackedMysqldResource(dbDir, dataDir, null, out, err, debug);

        final Map<String, String> dbOpts = new HashMap<String, String>();
        dbOpts.put(MysqldResourceI.PORT, Integer.toString(port));
        dbOpts.put(MysqldResourceI.INITIALIZE_USER, "true");
        dbOpts.put(MysqldResourceI.INITIALIZE_PASSWORD, password);
        dbOpts.put(MysqldResourceI.INITIALIZE_USER_NAME, username);
        dbOpts.put("default-time-zone", "+00:00");

        mysqldResource.start("test-mysqld-thread", dbOpts);
        if (!mysqldResource.isRunning()) {
            throw new IllegalStateException("MySQL did not start.");
        } else {
            started.set(true);
            logger.info("MySQL started: " + getCmdLineConnectionString());
        }
    }

    private void stopMysql() throws IOException {
        if (mysqldResource != null) {
            try {
                mysqldResource.shutdown();
            } catch (final NullPointerException npe) {
                logger.warn("Failed to shutdown mysql properly ", npe);
            }
            try {
                deleteRecursive(dataDir);
                deleteRecursive(dbDir);
            } catch (final FileNotFoundException e) {
                throw new IOException(e);
            }

            started.set(false);
            logger.info("MySQL stopped: " + getCmdLineConnectionString());
        }
    }

    private static boolean deleteRecursive(final File path) throws FileNotFoundException {
        if (!path.exists()) {
            throw new FileNotFoundException(path.getAbsolutePath());
        }
        boolean ret = true;
        if (path.isDirectory()) {
            final File[] files = path.listFiles();
            if (files != null) {
                for (final File f : files) {
                    ret = ret && deleteRecursive(f);
                }
            }
        }
        return ret && path.delete();
    }
}
