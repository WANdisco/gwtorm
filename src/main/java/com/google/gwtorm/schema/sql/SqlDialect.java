// Copyright (C) 2011 The Android Open Source Project
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

import com.google.gwtorm.schema.ColumnModel;
import com.google.gwtorm.schema.RelationModel;
import com.google.gwtorm.schema.SequenceModel;
import com.google.gwtorm.server.OrmException;
import com.google.gwtorm.server.Sequence;
import com.google.gwtorm.server.StatementExecutor;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

public abstract class SqlDialect {
  private static final List<SqlDialect> DIALECTS =
      new CopyOnWriteArrayList<>();

  static {
    DIALECTS.add(new DialectDB2());
    DIALECTS.add(new DialectDerby());
    DIALECTS.add(new DialectH2());
    DIALECTS.add(new DialectPostgreSQL());
    DIALECTS.add(new DialectMySQL());
    DIALECTS.add(new DialectOracle());
    DIALECTS.add(new DialectMaxDB());
    DIALECTS.add(new DialectHANA());
  }

  public static void register(SqlDialect dialect) {
    DIALECTS.add(0, dialect);
  }

  public static SqlDialect getDialectFor(Connection c)
      throws SQLException, OrmException {
    String url = c.getMetaData().getURL();
    for (SqlDialect d : DIALECTS) {
      if (d.handles(url, c)) {
        return d.refine(c);
      }
    }
    throw new OrmException("No dialect known for " + url);
  }

  protected final Map<Class<?>, SqlTypeInfo> types;
  protected final Map<Integer, String> typeNames;

  protected SqlDialect() {
    types = new HashMap<>();
    types.put(Boolean.TYPE, new SqlBooleanTypeInfo());
    types.put(Short.TYPE, new SqlShortTypeInfo());
    types.put(Integer.TYPE, new SqlIntTypeInfo());
    types.put(Long.TYPE, new SqlLongTypeInfo());
    types.put(Character.TYPE, new SqlCharTypeInfo());
    types.put(String.class, new SqlStringTypeInfo());
    types.put(java.sql.Date.class, new SqlDateTypeInfo());
    types.put(java.sql.Timestamp.class, new SqlTimestampTypeInfo());
    types.put(byte[].class, new SqlByteArrayTypeInfo());

    typeNames = new HashMap<>();
    typeNames.put(Types.VARBINARY, "BLOB");
    typeNames.put(Types.DATE, "DATE");
    typeNames.put(Types.SMALLINT, "SMALLINT");
    typeNames.put(Types.INTEGER, "INT");
    typeNames.put(Types.BIGINT, "BIGINT");
    typeNames.put(Types.LONGVARCHAR, "TEXT");
    typeNames.put(Types.TIMESTAMP, "TIMESTAMP");
  }

  public abstract boolean handles(String url, Connection c) throws SQLException;

  /** Select a better dialect definition for this connection.
   *
   * @param c the connection
   * @return a dialect instance
   * @throws SQLException
   */
  public SqlDialect refine(final Connection c) throws SQLException  {
    return this;
  }

  public String getSqlTypeName(final int typeCode) {
    final String r = typeNames.get(typeCode);
    return r != null ? r : "UNKNOWNTYPE";
  }

  public SqlTypeInfo getSqlTypeInfo(final ColumnModel col) {
    return getSqlTypeInfo(col.getPrimitiveType());
  }

  public SqlTypeInfo getSqlTypeInfo(final Class<?> t) {
    return types.get(t);
  }

  public String getParameterPlaceHolder(final int nthParameter) {
    return "?";
  }

  public boolean selectHasLimit() {
    return true;
  }

  protected static String getSQLState(SQLException err) {
    String ec;
    SQLException next = err;
    do {
      ec = next.getSQLState();
      next = next.getNextException();
    } while (ec == null && next != null);
    return ec;
  }

  protected static int getSQLStateInt(SQLException err) {
    final String s = getSQLState(err);
    if (s != null) {
      try {
        return Integer.parseInt(s);
      } catch (NumberFormatException e) {
        return -1;
      }
    }
    return 0;
  }

  /**
   * Convert a driver specific exception into an {@link OrmException}.
   *
   * @param op short description of the operation, e.g. "update" or "fetch".
   * @param entity name of the entity being accessed by the operation.
   * @param err the driver specific exception.
   * @return an OrmException the caller can throw.
   */
  public OrmException convertError(final String op, final String entity,
      final SQLException err) {
    if (err.getCause() == null && err.getNextException() != null) {
      err.initCause(err.getNextException());
    }
    return new OrmException(op + " failure on " + entity, err);
  }

  public long nextLong(final Connection conn, final String poolName)
      throws OrmException {
    final String query = getNextSequenceValueSql(poolName);
    try {
      final Statement st = conn.createStatement();
      try {
        final ResultSet rs = st.executeQuery(query);
        try {
          if (!rs.next()) {
            throw new SQLException("No result row for sequence query");
          }
          final long r = rs.getLong(1);
          if (rs.next()) {
            throw new SQLException("Too many results from sequence query");
          }
          return r;
        } finally {
          rs.close();
        }
      } finally {
        st.close();
      }
    } catch (SQLException e) {
      throw convertError("sequence", query, e);
    }
  }

  public String getCreateSequenceSql(final SequenceModel seq) {
    final Sequence s = seq.getSequence();
    final StringBuilder r = new StringBuilder();
    r.append("CREATE SEQUENCE ");
    r.append(seq.getSequenceName());

    /*
     * Some gwtorm users seems to imply a start of 1, enforce this constraint
     * here explicitly
     */
    r.append(" START WITH ");
    r.append(s.startWith() > 0 ? s.startWith() : 1);

    if (s.cache() > 0) {
      r.append(" CACHE ");
      r.append(s.cache());
    }

    return r.toString();
  }

  public String getDropSequenceSql(final String name) {
    return "DROP SEQUENCE " + name;
  }

  /**
   * Append driver specific storage parameters to a CREATE TABLE statement.
   *
   * @param sqlBuffer buffer holding the CREATE TABLE, just after the closing
   *        parenthesis after the column list.
   * @param relationModel the model of the table being generated.
   */
  public void appendCreateTableStorage(final StringBuilder sqlBuffer,
      final RelationModel relationModel) {
  }

  /**
   * List all tables in the current database schema.
   *
   * @param db connection to the schema.
   * @return set of declared tables, in lowercase.
   * @throws SQLException the tables cannot be listed.
   */
  public Set<String> listTables(final Connection db) throws SQLException {
    final String[] types = new String[] {"TABLE"};
    final ResultSet rs = db.getMetaData().getTables(null, null, null, types);
    try {
      Set<String> tables = new HashSet<>();
      while (rs.next()) {
        tables.add(rs.getString("TABLE_NAME").toLowerCase());
      }
      return tables;
    } finally {
      rs.close();
    }
  }

  /**
   * Rename an existing table.
   *
   * @param e statement to use to execute the SQL command(s).
   * @param from source table name
   * @param to destination table name
   * @throws OrmException the table could not be renamed.
   */
  public void renameTable(StatementExecutor e, String from,
      String to) throws OrmException {
    final StringBuilder r = new StringBuilder();
    r.append("ALTER TABLE ");
    r.append(from);
    r.append(" RENAME TO ");
    r.append(to);
    r.append(" ");
    e.execute(r.toString());
  }

  /**
   * List all indexes for the given table name.
   *
   * @param db connection to the schema.
   * @param tableName the table to list indexes from, in lowercase.
   * @return set of declared indexes, in lowercase.
   * @throws SQLException the indexes cannot be listed.
   */
  public Set<String> listIndexes(final Connection db, String tableName)
      throws SQLException {
    final DatabaseMetaData meta = db.getMetaData();
    if (meta.storesUpperCaseIdentifiers()) {
      tableName = tableName.toUpperCase();
    } else if (meta.storesLowerCaseIdentifiers()) {
      tableName = tableName.toLowerCase();
    }

    ResultSet rs = meta.getIndexInfo(null, null, tableName, false, true);
    try {
      Set<String> indexes = new HashSet<>();
      while (rs.next()) {
        indexes.add(rs.getString("INDEX_NAME").toLowerCase());
      }
      return indexes;
    } finally {
      rs.close();
    }
  }

  /**
   * List all sequences in the current database schema.
   *
   * @param db connection to the schema.
   * @return set of declared sequences, in lowercase.
   * @throws SQLException the sequence objects cannot be listed.
   */
  public abstract Set<String> listSequences(final Connection db)
      throws SQLException;

  /**
   * List all columns in the given table name.
   *
   * @param db connection to the schema.
   * @param tableName the table to list columns from, in lowercase.
   * @return set of declared columns, in lowercase.
   * @throws SQLException the columns cannot be listed from the relation.
   */
  public Set<String> listColumns(final Connection db, String tableName)
      throws SQLException {
    final DatabaseMetaData meta = db.getMetaData();
    if (meta.storesUpperCaseIdentifiers()) {
      tableName = tableName.toUpperCase();
    } else if (meta.storesLowerCaseIdentifiers()) {
      tableName = tableName.toLowerCase();
    }

    ResultSet rs = meta.getColumns(null, null, tableName, null);
    try {
      HashSet<String> columns = new HashSet<>();
      while (rs.next()) {
        columns.add(rs.getString("COLUMN_NAME").toLowerCase());
      }
      return columns;
    } finally {
      rs.close();
    }
  }

  /**
   * Add one column to an existing table.
   *
   * @param e statement to use to execute the SQL command(s).
   * @param tableName table to add the column onto.
   * @param col definition of the column.
   * @throws OrmException the column could not be added.
   */
  public void addColumn(StatementExecutor e, String tableName, ColumnModel col)
      throws OrmException {
    final StringBuilder r = new StringBuilder();
    r.append("ALTER TABLE ");
    r.append(tableName);
    r.append(" ADD ");
    r.append(col.getColumnName());
    r.append(" ");
    r.append(getSqlTypeInfo(col).getSqlType(col, this));
    String check = getSqlTypeInfo(col).getCheckConstraint(col, this);
    if (check != null) {
      r.append(' ');
      r.append(check);
    }
    e.execute(r.toString());
  }

  /**
   * Drop one column from an existing table.
   *
   * @param e statement to use to execute the SQL command(s).
   * @param tableName table to add the column onto.
   * @param column name of the column to drop.
   * @throws OrmException the column could not be added.
   */
  public void dropColumn(StatementExecutor e, String tableName, String column)
      throws OrmException {
    final StringBuilder r = new StringBuilder();
    r.append("ALTER TABLE ");
    r.append(tableName);
    r.append(" DROP COLUMN ");
    r.append(column);
    e.execute(r.toString());
  }

  /**
   * Rename an existing column in a table.
   *
   * @param e statement to use to execute the SQL command(s).
   * @param tableName table to rename the column in.
   * @param fromColumn source column name
   * @param col destination column definition
   * @throws OrmException the column could not be renamed.
   */
  public abstract void renameColumn(StatementExecutor e, String tableName,
      String fromColumn, ColumnModel col) throws OrmException;

  /**
   * Drop one index from a table.
   *
   * @param e statement to use to execute the SQL command(s).
   * @param tableName table to rename the index in.
   * @param name index name.
   * @throws OrmException the index could not be renamed.
   */
  public void dropIndex(StatementExecutor e, String tableName, String name)
      throws OrmException {
    e.execute(getDropIndexSql(tableName, name));
  }

  protected String getDropIndexSql(String tableName, String name) {
    return "DROP INDEX " + name;
  }

  protected abstract String getNextSequenceValueSql(String seqname);

  /**
   * Does the array returned by the PreparedStatement.executeBatch method return
   * the exact number of rows updated for every row in the batch?
   *
   * @return <code>true</code> if the executeBatch method returns the number of
   *         rows affected for every row in the batch; <code>false</code> if it
   *         may return Statement.SUCESS_NO_INFO
   */
  public boolean canDetermineIndividualBatchUpdateCounts() {
    return true;

  }

  /**
   * Can the total number of rows updated by the PreparedStatement.executeBatch
   * be determined exactly by the SQLDialect.executeBatch method?
   *
   * @return <code>true</code> if the SQlDialect.executeBatch method can exactly
   *         determine the total number of rows updated by a batch;
   *         <code>false</code> otherwise
   * @see #executeBatch(PreparedStatement)
   */
  public boolean canDetermineTotalBatchUpdateCount() {
    return true;
  }

  /**
   * Executes a prepared statement batch and returns the total number of rows
   * successfully updated or inserted. This method is intended to be overridden.
   *
   * If the canDetermineTotalBatchUpdateCount returns false for a particular
   * SQLDialect, this method should throw an UnsupportedOperationException.
   *
   * @param ps the prepared statement with the batch to be executed
   * @return the total number of rows affected
   * @see #canDetermineIndividualBatchUpdateCounts()
   */
  public int executeBatch(PreparedStatement ps) throws SQLException {
    final int[] updateCounts = ps.executeBatch();
    if (updateCounts == null) {
      throw new SQLException("No rows affected");
    }
    int totalUpdateCount = 0;
    for (int i = 0; i < updateCounts.length; i++) {
      int updateCount = updateCounts[i];
      if (updateCount > 0) {
        totalUpdateCount += updateCount;
      }
    }
    return totalUpdateCount;
  }

  /**
   * Some databases don't support delimiters (semicolons) in scripts.
   *
   * @return <code>true</code> statement delimiter is accepted,
   *         <code>false</code> otherwise
   */
  public boolean isStatementDelimiterSupported() {
    return true;
  }

  /**
   * Get the SQL LIMIT command segment in the given dialect
   * @param limit the limit to apply to the result set (either a number or ?)
   * @return the SQL LIMIT command segment in the given dialect
   */
  public String getLimitSql(String limit) {
    return "LIMIT " + limit;
  }

  /**
   * Get the driver specific 'table type' to be used in a CREATE TABLE
   * statement. When creating a CREATE TABLE statement the 'table type' is
   * appended after a blank following the CREATE keyword.
   */
  public String getTableTypeSql() {
    return "TABLE";
  }
}
