// Copyright (C) 2016 The Android Open Source Project
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

import static java.util.Arrays.asList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeNoException;

import com.google.gwtorm.data.Address;
import com.google.gwtorm.data.Person;
import com.google.gwtorm.data.PhoneBookDb;
import com.google.gwtorm.data.PhoneBookDb2;
import com.google.gwtorm.jdbc.Database;
import com.google.gwtorm.jdbc.JdbcExecutor;
import com.google.gwtorm.jdbc.JdbcSchema;
import com.google.gwtorm.jdbc.SimpleDataSource;
import com.google.gwtorm.server.OrmDuplicateKeyException;
import com.google.gwtorm.server.OrmException;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Collections;
import java.util.Properties;
import java.util.Set;

public class DialectHANATest extends SqlDialectTest {
  private static final String HANA_URL_KEY = "hana.url";
  private static final String HANA_USER_KEY = "hana.user";
  private static final String HANA_PASSWORD_KEY = "hana.password";
  private static final String HANA_DRIVER = "com.sap.db.jdbc.Driver";

  @Before
  public void setUp() throws Exception {
    try {
      Class.forName(HANA_DRIVER);
    } catch (Exception e) {
      assumeNoException(e);
    }

    String url = System.getProperty(HANA_URL_KEY);
    String user = System.getProperty(HANA_USER_KEY);
    String pass = System.getProperty(HANA_PASSWORD_KEY);

    db = DriverManager.getConnection(url, user, pass);
    executor = new JdbcExecutor(db);
    dialect = new DialectHANA().refine(db);

    Properties p = new Properties();
    p.setProperty("driver", HANA_DRIVER);
    p.setProperty("url", db.getMetaData().getURL());
    p.setProperty("user", user);
    p.setProperty("password", pass);
    phoneBook = new Database<>(new SimpleDataSource(p), PhoneBookDb.class);
    phoneBook2 = new Database<>(new SimpleDataSource(p), PhoneBookDb2.class);
  }

  private void drop(String drop) {
    try {
      execute("DROP " + drop);
    } catch (OrmException e) {
    }
  }

  @After
  public void tearDown() {
    if (executor == null) {
      return;
    }

    // Database content must be flushed because
    // tests assume that the database is empty
    drop("SEQUENCE address_id");
    drop("SEQUENCE cnt");

    drop("TABLE addresses");
    drop("TABLE foo");
    drop("TABLE bar");
    drop("TABLE people");

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

  private void execute(String sql) throws OrmException {
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
    PhoneBookDb p = phoneBook.open();
    try {
      p.updateSchema(executor);

      execute("CREATE SEQUENCE cnt");
      execute("CREATE TABLE foo (cnt INT)");

      execute("ALTER TABLE people ADD (fake_name NVARCHAR(20))");
      execute("ALTER TABLE people DROP (registered)");
      execute("DROP TABLE addresses");
      execute("DROP SEQUENCE address_id");

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

      Person.Key pk = new Person.Key("Bob");
      Person bob = new Person(pk, p.nextAddressId());
      p.people().insert(asList(bob));

      Address addr = new Address(new Address.Key(pk, "home"), "some place");
      p.addresses().insert(asList(addr));
    } finally {
      p.close();
    }

    PhoneBookDb2 p2 = phoneBook2.open();
    try {
      ((JdbcSchema) p2).renameField(executor, "people", "registered",
          "isRegistered");
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
    PhoneBookDb p = phoneBook.open();
    try {
      ((JdbcSchema) p).renameTable(executor, "foo", "bar");
    } finally {
      p.close();
    }
    s = dialect.listTables(db);
    assertTrue(s.contains("bar"));
    assertFalse(s.contains("for"));
  }

  @Test
  public void testInsert() throws OrmException {
    PhoneBookDb p = phoneBook.open();
    try {
      p.updateSchema(executor);

      Person.Key pk = new Person.Key("Bob");
      Person bob = new Person(pk, p.nextAddressId());
      p.people().insert(asList(bob));

      try {
        p.people().insert(asList(bob));
        fail();
      } catch (OrmDuplicateKeyException duprec) {
        // expected
      }
    } finally {
      p.close();
    }
  }

  @Test
  public void testUpdate() throws OrmException {
    PhoneBookDb p = phoneBook.open();
    try {
      p.updateSchema(executor);

      Person.Key pk = new Person.Key("Bob");
      Person bob = new Person(pk, p.nextAddressId());
      bob.setAge(40);
      p.people().insert(asList(bob));

      bob.setAge(50);
      p.people().update(asList(bob));

      bob = p.people().get(pk);
      assertEquals(50, bob.age());
    } finally {
      p.close();
    }
  }

  @Test
  public void testUpsert() throws OrmException {
    PhoneBookDb p = phoneBook.open();
    try {
      p.updateSchema(executor);

      Person.Key bobPk = new Person.Key("Bob");
      Person bob = new Person(bobPk, p.nextAddressId());
      bob.setAge(40);
      p.people().insert(asList(bob));

      Person.Key joePk = new Person.Key("Joe");
      Person joe = new Person(joePk, p.nextAddressId());
      bob.setAge(50);
      p.people().upsert(asList(bob, joe));

      bob = p.people().get(bobPk);
      assertEquals(50, bob.age());
      assertNotNull(p.people().get(joePk));
    } finally {
      p.close();
    }
  }
}
