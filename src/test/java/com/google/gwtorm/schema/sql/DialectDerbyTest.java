// Copyright 2015 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gwtorm.schema.sql;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.gwtorm.data.Address;
import com.google.gwtorm.data.Person;
import com.google.gwtorm.data.PhoneBookDb;
import com.google.gwtorm.data.PhoneBookDb2;
import com.google.gwtorm.jdbc.Database;
import com.google.gwtorm.jdbc.JdbcExecutor;
import com.google.gwtorm.jdbc.JdbcSchema;
import com.google.gwtorm.jdbc.SimpleDataSource;
import com.google.gwtorm.server.OrmException;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.Set;

public class DialectDerbyTest extends SqlDialectTest {

  private static final String CONNECT = "jdbc:derby:memory:DialectDerbyTest";

  @Before
  public void setUp() throws Exception {
    db = DriverManager.getConnection(CONNECT + ";create=true");
    executor = new JdbcExecutor(db);
    dialect = new DialectDerby().refine(db);

    final Properties p = new Properties();
    p.setProperty("driver", org.apache.derby.jdbc.EmbeddedDriver.class.getName());
    p.setProperty("url", db.getMetaData().getURL());

    phoneBook =
        new Database<>(new SimpleDataSource(p), PhoneBookDb.class);
    phoneBook2 =
        new Database<>(new SimpleDataSource(p), PhoneBookDb2.class);
  }

  @After
  public void tearDown() {
    try {
      DriverManager.getConnection(CONNECT + ";drop=true");
    } catch (SQLException e) {
      /* as stated in the derby reference manual,
       * section "Setting attributes for the database connection URL"
       * https://db.apache.org/derby/docs/10.11/ref/rrefattribdrop.html
       * "The attribute generates the SQLException 08006 if successful.
       *  If the database does not exist, it generates an error
       *  reporting that the database could not be found."
       *  So we can safely ignore this SQLException here.
       */
    }

    if (executor != null) {
      executor.close();
    }
    executor = null;

    if (db != null) {
      try {
        db.close();
      } catch (SQLException e) {
        throw new RuntimeException("Cannot close database", e);
      }
    }
    db = null;
  }

  private void execute(final String sql) throws OrmException {
    executor.execute(sql);
  }

  @Test
  public void testListSequences() throws OrmException, SQLException {
    assertTrue(dialect.listSequences(db).isEmpty());

    execute("CREATE SEQUENCE cnt");
    execute("CREATE TABLE foo (cnt INT)");

    Set<String> s = dialect.listSequences(db);
    assertEquals(1, s.size());
    assertTrue(s.contains("cnt"));
    assertFalse(s.contains("foo"));
  }

  @Test
  public void testListTables() throws OrmException, SQLException {
    assertTrue(dialect.listTables(db).isEmpty());

    execute("CREATE SEQUENCE cnt");
    execute("CREATE TABLE foo (cnt INT)");

    Set<String> s = dialect.listTables(db);
    assertEquals(1, s.size());
    assertFalse(s.contains("cnt"));
    assertTrue(s.contains("foo"));
  }

  @Test
  public void testListIndexes() throws OrmException, SQLException {
    assertTrue(dialect.listTables(db).isEmpty());

    execute("CREATE SEQUENCE cnt");
    execute("CREATE TABLE foo (cnt INT, bar INT, baz INT)");
    execute("CREATE UNIQUE INDEX FOO_PRIMARY_IND ON foo(cnt)");
    execute("CREATE INDEX FOO_SECOND_IND ON foo(bar, baz)");

    Set<String> s = dialect.listIndexes(db, "foo");
    assertEquals(2, s.size());
    assertTrue(s.contains("foo_primary_ind"));
    assertTrue(s.contains("foo_second_ind"));

    dialect.dropIndex(executor, "foo", "foo_primary_ind");
    dialect.dropIndex(executor, "foo", "foo_second_ind");
    assertEquals(Collections.emptySet(), dialect.listIndexes(db, "foo"));
  }

  @Test
  public void testUpgradeSchema() throws SQLException, OrmException {
    final PhoneBookDb p = phoneBook.open();
    try {
      p.updateSchema(executor);

      execute("CREATE SEQUENCE cnt");
      execute("CREATE TABLE foo (cnt INT)");

      execute("ALTER TABLE people ADD COLUMN fake_name VARCHAR(20)");
      execute("ALTER TABLE people DROP COLUMN registered");

      Set<String> sequences, tables;

      p.updateSchema(executor);
      sequences = dialect.listSequences(db);
      tables = dialect.listTables(db);
      assertTrue(sequences.contains("cnt"));
      assertTrue(tables.contains("foo"));

      assertTrue(sequences.contains("address_id"));
      assertTrue(tables.contains("addresses"));

      p.pruneSchema(executor);
      sequences = dialect.listSequences(db);
      tables = dialect.listTables(db);
      assertFalse(sequences.contains("cnt"));
      assertFalse(tables.contains("foo"));

      final Person.Key pk = new Person.Key("Bob");
      final Person bob = new Person(pk, p.nextAddressId());
      p.people().insert(Collections.singleton(bob));

      final Address addr =
          new Address(new Address.Key(pk, "home"), "some place");
      p.addresses().insert(Collections.singleton(addr));
    } finally {
      p.close();
    }

    /* rename a column in an existing table to match the new schema */
    final PhoneBookDb2 p2 = phoneBook2.open();

    /* As Apache Derby doesn't support renaming a column as long as a constraint
     * exists on the column, we need to drop the constraint manually.
     * Currently a CHECK constraint is created by gwtorm for all fields with
     * type SqlBooleanTypeInfo.
     * See also https://db.apache.org/derby/docs/10.11/ref/rrefsqljrenamecolumnstatement.html
     * for more information about the restriction of the RENAME COLUMN command.
     */
    String sqlCatalog =
          "select c.constraintname, ch.checkdefinition"
        + " from sys.sysconstraints c, sys.systables t, sys.syschecks ch"
        + " WHERE c.tableid = t.tableid"
        + "   and t.tablename = ?"
        + "   and c.constraintid = ch.constraintid";
    PreparedStatement ps = db.prepareStatement(sqlCatalog);
    String tableName = "PEOPLE";
    ps.setString(1, tableName);
    try {
      ResultSet rs = ps.executeQuery();
      List<String> constraintNames = new ArrayList<>();
      List<String> checkDefs = new ArrayList<>();
      while (rs.next()) {
        if (rs.getString(2).contains("registered")) {
          constraintNames.add(rs.getString(1));
          checkDefs.add(rs.getString(2).replace("registered", "is_registered"));
          break;
         }
      }
      rs.close();
      ps.close();
      if (constraintNames.isEmpty()) {
        fail("Constraint not found");
      }
      for (String c: constraintNames) {
        execute("alter table " + tableName + " drop check " + c);
      }
      ((JdbcSchema) p2).renameField(executor, "people", "registered",
          "isRegistered");
      for (String cd: checkDefs) {
        execute("alter table " + tableName + " add check "+ cd);
      }
    } catch (OrmException e) {
      fail(e.getMessage());
    } finally {
      p2.close();
    }
  }

  @Test
  public void testRenameTable() throws SQLException, OrmException {
    assertTrue(dialect.listTables(db).isEmpty());
    execute("CREATE TABLE foo (cnt INT)");
    Set<String> s = dialect.listTables(db);
    assertEquals(1, s.size());
    assertTrue(s.contains("foo"));
    final PhoneBookDb p = phoneBook.open();
    try {
      ((JdbcSchema) p).renameTable(executor, "foo", "bar");
    } finally {
      p.close();
    }
    s = dialect.listTables(db);
    assertTrue(s.contains("bar"));
    assertFalse(s.contains("for"));
  }
}
