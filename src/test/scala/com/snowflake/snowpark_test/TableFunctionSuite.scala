package com.snowflake.snowpark_test

import com.snowflake.snowpark.functions._
import com.snowflake.snowpark.{Row, _}

import scala.collection.Seq
import scala.collection.immutable.Map

class TableFunctionSuite extends TestData {
  import session.implicits._

  test("dataframe join table function") {
    val df = Seq("[1,2]", "[3,4]").toDF("a")

    checkAnswer(
      df.join(tableFunctions.flatten, Map("input" -> parse_json(df("a")))).select("value"),
      Seq(Row("1"), Row("2"), Row("3"), Row("4")),
      sort = false)
    checkAnswer(
      df.join(TableFunction("flatten"), Map("input" -> parse_json(df("a"))))
        .select("value"),
      Seq(Row("1"), Row("2"), Row("3"), Row("4")),
      sort = false)

    checkAnswer(
      df.join(tableFunctions.split_to_table, df("a"), lit(",")).select("value"),
      Seq(Row("[1"), Row("2]"), Row("[3"), Row("4]")),
      sort = false)
    checkAnswer(
      df.join(tableFunctions.split_to_table, Seq(df("a"), lit(","))).select("value"),
      Seq(Row("[1"), Row("2]"), Row("[3"), Row("4]")),
      sort = false)
    checkAnswer(
      df.join(TableFunction("split_to_table"), df("a"), lit(",")).select("value"),
      Seq(Row("[1"), Row("2]"), Row("[3"), Row("4]")),
      sort = false)
  }

  test("session table functions") {
    checkAnswer(
      session
        .tableFunction(tableFunctions.flatten, Map("input" -> parse_json(lit("[1,2]"))))
        .select("value"),
      Seq(Row("1"), Row("2")),
      sort = false)
    checkAnswer(
      session
        .tableFunction(TableFunction("flatten"), Map("input" -> parse_json(lit("[1,2]"))))
        .select("value"),
      Seq(Row("1"), Row("2")),
      sort = false)

    checkAnswer(
      session
        .tableFunction(tableFunctions.split_to_table, lit("split by space"), lit(" "))
        .select("value"),
      Seq(Row("split"), Row("by"), Row("space")),
      sort = false)
    checkAnswer(
      session
        .tableFunction(tableFunctions.split_to_table, Seq(lit("split by space"), lit(" ")))
        .select("value"),
      Seq(Row("split"), Row("by"), Row("space")),
      sort = false)
    checkAnswer(
      session
        .tableFunction(TableFunction("split_to_table"), lit("split by space"), lit(" "))
        .select("value"),
      Seq(Row("split"), Row("by"), Row("space")),
      sort = false)
  }

  test("session table functions with dataframe columns") {
    val df = Seq(("split by space", "another column"), ("", "")).init.toDF(Seq("a", "b"))
    checkAnswer(
      session
        .tableFunction(tableFunctions.split_to_table, Seq(df("a"), lit(" ")))
        .select("value"),
      Seq(Row("split"), Row("by"), Row("space")),
      sort = false)
    checkAnswer(
      session
        .tableFunction(TableFunction("split_to_table"), Seq(df("a"), lit(" ")))
        .select("value"),
      Seq(Row("split"), Row("by"), Row("space")),
      sort = false)

    val df2 = Seq(("[1,2]", "[5,6]"), ("[3,4]", "[7,8]")).toDF(Seq("a", "b"))
    checkAnswer(
      session
        .tableFunction(tableFunctions.flatten, Map("input" -> parse_json(df2("b"))))
        .select("value"),
      Seq(Row("5"), Row("6"), Row("7"), Row("8")),
      sort = false)
    checkAnswer(
      session
        .tableFunction(TableFunction("flatten"), Map("input" -> parse_json(df2("b"))))
        .select("value"),
      Seq(Row("5"), Row("6"), Row("7"), Row("8")),
      sort = false)

    val df3 = Seq("[9, 10]").toDF("c")
    val dfJoined = df2.join(df3)
    checkAnswer(
      session
        .tableFunction(tableFunctions.flatten, Map("input" -> parse_json(dfJoined("b"))))
        .select("value"),
      Seq(Row("5"), Row("6"), Row("7"), Row("8")),
      sort = false)

    val tableName = randomName()
    try {
      df.write.mode(SaveMode.Overwrite).saveAsTable(tableName)
      val df4 = session.table(tableName)
      checkAnswer(
        session
          .tableFunction(tableFunctions.split_to_table, Seq(df4("a"), lit(" ")))
          .select("value"),
        Seq(Row("split"), Row("by"), Row("space")),
        sort = false)
    } finally {
      dropTable(tableName)
    }
  }

  test("Schema String lateral join flatten function") {
    val tableName = randomName()
    try {
      val df = Seq("a", "b").toDF("values")
      val aggDf = df.agg(array_agg(col("values")).alias("value"))
      aggDf.write.mode(SaveMode.Overwrite).saveAsTable(tableName)
      val table = session.table(tableName)
      val flattened = table.flatten(table("value"))
      checkAnswer(
        flattened.select(table("value"), flattened("value").as("newValue")),
        Seq(Row("[\n  \"a\",\n  \"b\"\n]", "\"a\""), Row("[\n  \"a\",\n  \"b\"\n]", "\"b\"")))
    } finally {
      dropTable(tableName)
    }
  }

  // SNOW-388226
  test("split to table from file") {
    val df = Seq(
      "NAME\tSample1\tSample2\tSample3",
      "Obs1\t-0.74\t-0.2\t0.3",
      "Obs2\t5442\t0.19\t0.16",
      "Obs3\t0.34\t0.46\t0.72",
      "Obs4\t-0.15\t0.71\t0.13").toDF("line")

    val flattenedMatrix = df
      .withColumn("rowLabel", split(col("line"), lit("\t"))(0))
      .join(tableFunctions.split_to_table, col("line"), lit("\t"))
      .select("rowLabel", "SEQ", "INDEX", "VALUE")

    val colNames = flattenedMatrix
      .filter(col("SEQ") === 1)
      .select(col("INDEX"), col("VALUE").as("colName"))

    val dataRows = flattenedMatrix
      .filter(col("SEQ") > 1 && col("INDEX") > 1)
      .select(col("rowLabel"), col("INDEX"), col("SEQ"), col("VALUE").as("cellValue"))

    val kvMatrix = colNames
      .join(dataRows, colNames("INDEX") === dataRows("INDEX"), "inner")
      .select(col("rowLabel"), col("colName"), col("cellValue"))

    assert(
      getShowString(kvMatrix, 20) ==
        """----------------------------------------
     ||"ROWLABEL"  |"COLNAME"  |"CELLVALUE"  |
     |----------------------------------------
     ||"Obs1"      |Sample1    |-0.74        |
     ||"Obs2"      |Sample1    |5442         |
     ||"Obs3"      |Sample1    |0.34         |
     ||"Obs4"      |Sample1    |-0.15        |
     ||"Obs1"      |Sample2    |-0.2         |
     ||"Obs2"      |Sample2    |0.19         |
     ||"Obs3"      |Sample2    |0.46         |
     ||"Obs4"      |Sample2    |0.71         |
     ||"Obs1"      |Sample3    |0.3          |
     ||"Obs2"      |Sample3    |0.16         |
     ||"Obs3"      |Sample3    |0.72         |
     ||"Obs4"      |Sample3    |0.13         |
     |----------------------------------------
     |""".stripMargin)
  }

  test("Argument in table function: flatten") {
    val df = Seq(
      (1, Array(1, 2, 3), Map("a" -> "b", "c" -> "d")),
      (2, Array(11, 22, 33), Map("a1" -> "b1", "c1" -> "d1"))).toDF("idx", "arr", "map")
    checkAnswer(
      df.join(tableFunctions.flatten(df("arr")))
        .select("value"),
      Seq(Row("1"), Row("2"), Row("3"), Row("11"), Row("22"), Row("33")))
    // error if it is not a table function
    val error1 = intercept[SnowparkClientException] { df.join(lit("dummy")) }
    assert(
      error1.message.contains("Unsupported join operations, Dataframes can join " +
        "with other Dataframes or TableFunctions only"))
  }

  test("Argument in table function: flatten2") {
    val df1 = Seq("{\"a\":1, \"b\":[77, 88]}").toDF("col")
    checkAnswer(
      df1
        .join(
          tableFunctions.flatten(
            input = parse_json(df1("col")),
            path = "b",
            outer = true,
            recursive = true,
            mode = "both"))
        .select("value"),
      Seq(Row("77"), Row("88")))

    val df2 = Seq("[]").toDF("col")
    checkAnswer(
      df2
        .join(
          tableFunctions.flatten(
            input = parse_json(df1("col")),
            path = "",
            outer = true,
            recursive = true,
            mode = "both"))
        .select("value"),
      Seq(Row(null)))

    assert(
      df1
        .join(
          tableFunctions.flatten(
            input = parse_json(df1("col")),
            path = "",
            outer = true,
            recursive = true,
            mode = "both"))
        .count() == 4)
    assert(
      df1
        .join(
          tableFunctions.flatten(
            input = parse_json(df1("col")),
            path = "",
            outer = true,
            recursive = false,
            mode = "both"))
        .count() == 2)
    assert(
      df1
        .join(
          tableFunctions.flatten(
            input = parse_json(df1("col")),
            path = "",
            outer = true,
            recursive = true,
            mode = "array"))
        .count() == 1)
    assert(
      df1
        .join(
          tableFunctions.flatten(
            input = parse_json(df1("col")),
            path = "",
            outer = true,
            recursive = true,
            mode = "object"))
        .count() == 2)
  }

  test("Argument in table function: flatten - session") {
    val df = Seq(
      (1, Array(1, 2, 3), Map("a" -> "b", "c" -> "d")),
      (2, Array(11, 22, 33), Map("a1" -> "b1", "c1" -> "d1"))).toDF("idx", "arr", "map")
    checkAnswer(
      session.tableFunction(tableFunctions.flatten(df("arr"))).select("value"),
      Seq(Row("1"), Row("2"), Row("3"), Row("11"), Row("22"), Row("33")))
    // error if it is not a table function
    val error1 = intercept[SnowparkClientException] {
      session.tableFunction(lit("dummy"))
    }
    assert(
      error1.message.contains("Invalid input argument, " +
        "Session.tableFunction only supports table function arguments"))
  }

  test("Argument in table function: flatten - session 2") {
    val df1 = Seq("{\"a\":1, \"b\":[77, 88]}").toDF("col")
    checkAnswer(
      session
        .tableFunction(
          tableFunctions.flatten(
            input = parse_json(df1("col")),
            path = "b",
            outer = true,
            recursive = true,
            mode = "both"))
        .select("value"),
      Seq(Row("77"), Row("88")))
  }

  test("Argument in table function: split_to_table") {
    val df = Seq("1,2", "3,4").toDF("data")

    checkAnswer(
      df.join(tableFunctions.split_to_table(df("data"), ",")).select("value"),
      Seq(Row("1"), Row("2"), Row("3"), Row("4")))

    checkAnswer(
      session
        .tableFunction(tableFunctions.split_to_table(df("data"), ","))
        .select("value"),
      Seq(Row("1"), Row("2"), Row("3"), Row("4")))
  }

  test("Argument in table function: table function") {
    val df = Seq("1,2", "3,4").toDF("data")

    checkAnswer(
      df.join(TableFunction("split_to_table")(df("data"), lit(",")))
        .select("value"),
      Seq(Row("1"), Row("2"), Row("3"), Row("4")))

    val df1 = Seq("{\"a\":1, \"b\":[77, 88]}").toDF("col")
    checkAnswer(
      session
        .tableFunction(
          TableFunction("flatten")(
            Map(
              "input" -> parse_json(df1("col")),
              "path" -> lit("b"),
              "outer" -> lit(true),
              "recursive" -> lit(true),
              "mode" -> lit("both"))))
        .select("value"),
      Seq(Row("77"), Row("88")))
  }
}
