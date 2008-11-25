// Copyright 2008 Google Inc.
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

import com.google.gwtorm.client.Column;
import com.google.gwtorm.schema.ColumnModel;

import java.sql.Types;

public class SqlTimestampTypeInfo extends SqlTypeInfo {
  @Override
  protected String getJavaSqlTypeAlias() {
    return "Timestamp";
  }

  @Override
  protected int getSqlTypeConstant() {
    return Types.TIMESTAMP;
  }

  @Override
  public String getSqlType(final ColumnModel col, final SqlDialect dialect) {
    final Column column = col.getColumnAnnotation();
    final StringBuilder r = new StringBuilder();
    r.append(dialect.getSqlTypeName(getSqlTypeConstant()));
    if (column.notNull()) {
      r.append(" DEFAULT '1900-01-01 00:00:00'");
      r.append(" NOT NULL");
    }
    return r.toString();
  }
}
