package jp.ne.opt.redshiftfake.parse

import jp.ne.opt.redshiftfake.parse.compat.CompatibilityHandler
import net.sf.jsqlparser.parser.CCJSqlParserUtil

/**
  * Created by frankfarrell on 01/03/2018.
  *
  * Converts redshift alter table to postgres equivalents
  *
  * https://docs.aws.amazon.com/redshift/latest/dg/r_ALTER_TABLE.html#r_ALTER_TABLE-synopsis
  * =>
  * https://www.postgresql.org/docs/9.1/static/sql-altertable.html
  *
  * Cases
  * 1) Add column => No difference if NOT NULL and/or DEFAULT are not present
  * 2) Add column with default and/or not null => multiple postgresql statements, drop encoding.
  *   i) If NOT NULL and no default -> throw
  *   ii) If default is function with params => throw
  * 3) Rename table => jsql? No difference
  * 4) Rename column => jsql? No difference
  * 5) Drop column => jsql? No difference
  * 6) Add Contrainst => No difference
  * 7) Drop Constraint => No difference
  */
class AlterTableHandler extends BaseParser {

  val alterTableRegex = s"(?i)ALTER${space}TABLE$space$identifier".r
  val addColumn = s"(?i)ALTER${space}TABLE$space$identifier${space}ADD$space(COLUMN$space)?$identifier$space$dataTypeIdentifier".r
  val columnName = s"(?i)ALTER${space}TABLE$space$identifier${space}ADD$space(COLUMN$space)?".r ~> s"$identifier".r <~ s"$space$identifier".r

  val addColumnNotNull = s"(?i)$any*(NOT$space)?NULL".r

  val addColumnDefault = s"(?i)$any*DEFAULT$space".r ~> s"($identifier(\\(\\))|'$identifier')".r <~ s"$any*".r


  def matches(sql: String): Boolean ={
    return parse(alterTableRegex, sql).successful
  }

  def handle(sql: String): String ={
    // Do stuff above and return concatenated statements
    // Can execute handle multiple statements in 1?

    if(parse(addColumn, sql).successful) {

      var baseAddColumnStatement = parse(addColumn, sql).get

      val withDefault = parse(addColumnDefault, sql)

      if(withDefault.successful){
        val defaultValue = withDefault.get

        val parsedDefaultValue =
          if(defaultValue.contains("(")){
            //Parse the function and convert if necessary
            val parsed = CCJSqlParserUtil.parse("SELECT " + defaultValue)
            parsed.accept(new CompatibilityHandler)
            parsed.toString.replace("SELECT ", "")
          }
          else {
            defaultValue
          }

        var alterTableStatementBase =
          parse(alterTableRegex, sql).get +
            " ALTER COLUMN " +
            parse(columnName, sql).get +
            " SET DEFAULT " +
            parsedDefaultValue

        baseAddColumnStatement += ";" + alterTableStatementBase
      }

      val withNullClause = parse(addColumnNotNull, sql)

      if(withNullClause.successful){

        val setNull =
          if(withNullClause.get.contains("NOT")){
            " SET NOT NULL"
          }
          else{
            " SET NULL"
          }

        val nullStatement =
          parse(alterTableRegex, sql).get +
            " ALTER COLUMN " +
            parse(columnName, sql).get +
            setNull

        baseAddColumnStatement += ";" + nullStatement
      }
      baseAddColumnStatement
    }
    //All other cases require no change
    else{
      sql
    }
  }
}