package com.adverity.qg

import com.adverity.antlr.*
import org.antlr.v4.runtime.*
import org.antlr.v4.runtime.misc.ParseCancellationException
import org.antlr.v4.runtime.tree.ParseTree
import org.jooq.*
import org.jooq.impl.DSL
import java.math.BigDecimal

class AdverityParseExc(error: String): Exception(error)

data class Kpi(val measure: String)
data class FactSource(val name: String)
data class FactDimension(val name: String, val isLast: Boolean = false)
data class FactColumn(val name: String)

sealed class QueryExpr(val text: String) {
    class Column(name: String): QueryExpr(name)
    class Formula(formula: String): QueryExpr(formula)
}

typealias KpiDefs = Map<String, Kpi>
typealias DataMappings = Map<String, QueryExpr>
typealias VerifyKpi = (kpi: String) -> Boolean
typealias BuildKpiFormula = (kpi: String, kpis: KpiDefs, mappings: DataMappings, dimension: FactDimension) -> Field<BigDecimal>

class ThrowingErrorListener : BaseErrorListener() {
    @Throws(ParseCancellationException::class)
    override fun syntaxError(
        recognizer: Recognizer<*, *>?,
        offendingSymbol: Any?,
        line: Int,
        charPositionInLine: Int,
        msg: String,
        e: RecognitionException?
    ) {
        throw ParseCancellationException("line $line:$charPositionInLine $msg")
    }
    
    companion object {
        val INSTANCE = ThrowingErrorListener()
    }
}

class KpiCustomParser(tokens: CommonTokenStream) : KpiParser(tokens) {
    override fun storeAgg(agg: Token?) {
        println("AGG -> ${agg?.startIndex} -> ${agg?.stopIndex} : ${agg?.text}")
    }
}

internal class KpiFieldVisitor(
    val dimensions: FactDimension,
    val kpis: KpiDefs,
    val mappings: DataMappings,
    val verifyKpi: VerifyKpi,
    val buildKpiFormula: BuildKpiFormula
) : KpiBaseVisitor<Field<BigDecimal>>() {
    
    override fun aggregateResult(aggregate: Field<BigDecimal>?, nextResult: Field<BigDecimal>?): Field<BigDecimal>? {
        if (aggregate == null) {
            return nextResult
        }
        if (nextResult == null) {
            return aggregate
        }
        return DSL.field("", BigDecimal::class.java)
    }
    
    override fun visitMulDiv(ctx: KpiParser.MulDivContext): Field<BigDecimal> {
        if (ctx.op.type == KpiParser.MUL) {
            return visit(ctx.left).mul(visit(ctx.right))
        }
        return visit(ctx.left).div(visit(ctx.right))
    }
    
    override fun visitAddSub(ctx: KpiParser.AddSubContext): Field<BigDecimal> {
        if (ctx.op.type == KpiParser.ADD) {
            return visit(ctx.left).add(visit(ctx.right))
        }
        return visit(ctx.left).sub(visit(ctx.right))
    }
    
    override fun visitParenExpr(ctx: KpiParser.ParenExprContext): Field<BigDecimal> {
        return visit(ctx)
    }
    
    override fun visitAgg(ctx: KpiParser.AggContext): Field<BigDecimal>? {
        val rawKpiName = ctx.text.drop(1).dropLast(1)
        if (!verifyKpi(rawKpiName)) {
            throw AdverityParseExc("Undefined KPI $rawKpiName")
        }
        return buildKpiFormula(rawKpiName, kpis, mappings, dimensions)
    }
    
    override fun visitNumber(ctx: KpiParser.NumberContext): Field<BigDecimal> {
        return DSL.field(ctx.NUMBER().text, BigDecimal::class.java)
    }
    
    override fun visit(tree: ParseTree?): Field<BigDecimal> {
        return super.visit(tree)
    }
}

fun makeSelectAllQuery(ctx: DSLContext, tableName: String): SelectQuery<Record> {
    val q = ctx.selectQuery()
    q.addSelect(DSL.field("*"))
    q.addFrom(DSL.table(tableName))
    return q
}

fun parseKpi(kpi: String,
             dimension: FactDimension,
             kpis: KpiDefs,
             mappings: DataMappings,
             verifyKpi: VerifyKpi,
             buildKpiFormula: BuildKpiFormula
): Field<BigDecimal>? {
    val lexer = KpiLexer(CharStreams.fromString(kpi))
    lexer.removeErrorListeners()
    lexer.addErrorListener(ThrowingErrorListener.INSTANCE)
    val tokens = CommonTokenStream(lexer)
    val parser = KpiCustomParser(tokens)
    parser.removeErrorListeners()
    parser.addErrorListener(ThrowingErrorListener.INSTANCE)
    val tree: ParserRuleContext = parser.init()
    val visitor = KpiFieldVisitor(dimension, kpis, mappings, verifyKpi, buildKpiFormula)
    return visitor.visit(tree)
}

fun buildJooqQuery(ctx: DSLContext,
                   dimensions: List<FactDimension>,
                   columns: List<FactColumn>,
                   tables: List<FactSource>,
                   kpis: KpiDefs,
                   mappings: DataMappings,
                   verifyKpi: VerifyKpi,
                   buildKpiFormula: BuildKpiFormula
): SelectQuery<Record> {
    val q = ctx.selectQuery()
    
    q.addSelect(
        dimensions.mapNotNull { dimension -> 
            mappings[dimension.name]?.text
        }.map {
            DSL.field(it)
        }
    )
    
    q.addSelect(
        columns.flatMap { column ->
            when (val mapping = mappings[column.name]) {
                is QueryExpr.Column -> listOf(DSL.field(mapping.text))
                is QueryExpr.Formula -> {
                    dimensions.mapIndexed { index, d ->
                        val dim = mappings[d.name]?.text
                        parseKpi(mapping.text, d, kpis, mappings, verifyKpi, buildKpiFormula)?.`as`("kpi_${index}~${dim}")
                    }
                }
                else -> emptyList<Field<Any>>()
            }
        }
    )
    
    val innerUnion = makeSelectAllQuery(ctx, tables.first().name)
    tables.drop(1).forEach { t -> innerUnion.unionAll(makeSelectAllQuery(ctx, t.name)) }
    
    q.addFrom(innerUnion)
    dimensions.forEach {
        val fld = DSL.field(mappings[it.name]?.text) 
        q.addGroupBy(fld)
        q.addOrderBy(fld.asc().nullsLast())
    }
    return q
}

fun main() {
    val kpis: KpiDefs = mapOf(
        "clicks" to Kpi("sum"),
        "impressions" to Kpi("avg")
    )
    
    val mappings = mapOf(
        "Datasource" to QueryExpr.Column("datasource"),
        "Client" to QueryExpr.Column("client"),
        "MyKpi" to QueryExpr.Formula("(clicks)+(impressions)*2")
    )

    val verifyKpi = { text: String ->
        kpis.containsKey(text)
    }
    
    val makeAggFld = { measure: String, fld: Field<BigDecimal> ->
        when (measure) {
            "avg" -> DSL.avg(fld)
            else -> DSL.sum(fld)
        }
    }
    
    val buildKpiFormula: (String, KpiDefs, DataMappings, FactDimension) -> Field<BigDecimal> = { kpi: String, kpis: KpiDefs, mappings: DataMappings, dimension: FactDimension -> 
        val measure = kpis[kpi]?.measure ?: "sum"
        val fld = DSL.field(kpi, BigDecimal::class.java)
        val aggFld = makeAggFld(measure, fld)
        val dim = mappings[dimension.name]?.text
        if (!dimension.isLast) {
            makeAggFld(measure, aggFld).over().partitionBy(DSL.field(dim)) 
        } else {
            aggFld
        }
    }
    
    val dsl: DSLContext = DSL.using(SQLDialect.POSTGRES)
    
    val dimensions = listOf(FactDimension("Datasource"), FactDimension("Client", true))
    val columns = listOf(FactColumn("costs"), FactColumn("MyKpi"))
    val tables = listOf(FactSource("facts_5"), FactSource("facts_6"))
    
    val q = buildJooqQuery(dsl, dimensions, columns, tables, kpis, mappings, verifyKpi, buildKpiFormula)

    println(q.toString())
    
}
