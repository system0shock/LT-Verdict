package io.ltverdict.core

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PodViewQueryTest {
    private val view = validPodView(podViewReadFixture()).view
    private val sha = "c".repeat(64)

    @Test
    fun `metadata lists services with pod and row counts and no values`() {
        val meta = podViewMetadataJson(view, sha)

        assertEquals("pod-view.v1", meta.str("schema_version"))
        assertEquals("A", meta.str("arm"))
        assertEquals(sha, meta.str("pod_view_sha256"))
        assertEquals("b".repeat(64), meta.str("resource_snapshot_sha256"))
        val grid = meta.getValue("grid").jsonObject
        assertEquals(
            listOf(START, STEP, 4L),
            listOf("start_epoch_ms", "step_ms", "column_count").map {
                grid
                    .getValue(it)
                    .jsonPrimitive.content
                    .toLong()
            },
        )
        assertEquals(
            listOf("cart" to (2 to 5), "orders" to (1 to 2)),
            meta.getValue("services").jsonArray.map {
                val item = it.jsonObject
                item.str("service") to
                    (
                        item
                            .getValue("pods")
                            .jsonPrimitive.content
                            .toInt() to
                            item
                                .getValue("rows")
                                .jsonPrimitive.content
                                .toInt()
                    )
            },
        )
        assertEquals(
            "ALL",
            meta
                .getValue("coverage")
                .jsonObject
                .getValue("selection")
                .jsonObject
                .str("kind"),
        )
        assertFalse(meta.containsKey("rows"))
        assertFalse(meta.toString().contains("\"values\""))
    }

    @Test
    fun `metadata omits the arm for an artifact without one`() {
        val noArm = validPodView(podViewReadFixture(arm = null)).view

        assertEquals(JsonNull, podViewMetadataJson(noArm, sha).getValue("arm"))
    }

    @Test
    fun `pages follow the canonical order and end with a null cursor`() {
        val first = values(limit = 2)
        assertEquals(listOf("r1", "r2"), first.ids())
        assertEquals("r2", first.str("next_after"))

        val second = values(limit = 2, after = "r2")
        assertEquals(listOf("r3", "r4"), second.ids())
        assertEquals("r4", second.str("next_after"))

        val last = values(limit = 2, after = "r4")
        assertEquals(listOf("r5"), last.ids())
        assertEquals(JsonNull, last.getValue("next_after"))

        val whole = values(limit = 5)
        assertEquals(listOf("r1", "r2", "r3", "r4", "r5"), whole.ids())
        assertEquals(JsonNull, whole.getValue("next_after"))

        val tail = values(limit = 5, after = "r5")
        assertEquals(emptyList<String>(), tail.ids())
        assertEquals(JsonNull, tail.getValue("next_after"))
    }

    @Test
    fun `metric narrows the selection and the cursor must belong to it`() {
        val memory = values(metric = "container_memory_ratio")
        assertEquals(listOf("r2", "r3", "r5"), memory.ids())

        assertEquals(listOf("r5"), values(metric = "container_memory_ratio", after = "r3").ids())
        assertCursorRejected(metric = "container_memory_ratio", after = "r1")
        assertEquals(emptyList<String>(), values(metric = "no_such_metric").ids())
        assertCursorRejected(metric = "no_such_metric", after = "r1")
    }

    @Test
    fun `a cursor from another service or an unknown id is rejected`() {
        assertCursorRejected(after = "r6")
        assertCursorRejected(after = "missing")
        assertCursorRejected(after = "")
        assertEquals(listOf("r7"), values(service = "orders", after = "r6").ids())
    }

    @Test
    fun `an unknown service is reported as such`() {
        val failure = assertThrows(PodViewQueryException::class.java) { values(service = "billing") }

        assertEquals(PodViewQueryException.Kind.SERVICE_NOT_FOUND, failure.kind)
    }

    @Test
    fun `the window is cut on column boundaries and echoed`() {
        val page = values(from = START + STEP, to = START + 3 * STEP)

        val grid = page.getValue("grid").jsonObject
        assertEquals(
            START + STEP,
            grid
                .getValue("from_ms")
                .jsonPrimitive.content
                .toLong(),
        )
        assertEquals(
            START + 3 * STEP,
            grid
                .getValue("to_ms")
                .jsonPrimitive.content
                .toLong(),
        )
        assertEquals(
            2,
            grid
                .getValue("column_count")
                .jsonPrimitive.content
                .toInt(),
        )
        val first =
            page
                .getValue("rows")
                .jsonArray
                .first()
                .jsonObject
                .getValue("values")
                .jsonArray
        assertEquals(listOf(JsonPrimitive(0.25), JsonNull), first.toList())

        val full = values()
        assertEquals(
            START,
            full
                .getValue("grid")
                .jsonObject
                .getValue("from_ms")
                .jsonPrimitive.content
                .toLong(),
        )
        assertEquals(
            START + 4 * STEP,
            full
                .getValue("grid")
                .jsonObject
                .getValue("to_ms")
                .jsonPrimitive.content
                .toLong(),
        )
        assertEquals(
            4,
            full
                .getValue("rows")
                .jsonArray
                .first()
                .jsonObject
                .getValue("values")
                .jsonArray.size,
        )
    }

    @Test
    fun `a window off the column grid is rejected without rounding`() {
        listOf(
            START + 1 to null,
            START - STEP to null,
            START + 4 * STEP to null,
            null to START + STEP + 1,
            null to START,
            null to START + 5 * STEP,
            (START + 2 * STEP) to (START + 2 * STEP),
            (START + 2 * STEP) to (START + STEP),
        ).forEach { (from, to) ->
            val failure = assertThrows(PodViewQueryException::class.java, { values(from = from, to = to) }, "from=$from to=$to")
            assertEquals(PodViewQueryException.Kind.INVALID_QUERY, failure.kind, "from=$from to=$to")
        }
    }

    @Test
    fun `a page above the cell cap is refused instead of truncated`() {
        // The requested row limit, not the rows found, is multiplied by the columns: a filter cannot dodge the cap.
        val atCap = values(limit = 2, maxCells = 8)
        assertEquals(listOf("r1", "r2"), atCap.ids())

        val over = assertThrows(PodViewQueryException::class.java) { values(limit = 3, maxCells = 8) }
        assertEquals(PodViewQueryException.Kind.INVALID_QUERY, over.kind)
        assertTrue(over.message!!.contains("narrow"))

        val narrowed = values(limit = 3, from = START, to = START + 2 * STEP, maxCells = 8)
        assertEquals(listOf("r1", "r2", "r3"), narrowed.ids())

        assertThrows(PodViewQueryException::class.java) { values(service = "orders", limit = 3, maxCells = 8) }
    }

    @Test
    fun `the default page cap is above the largest page the limits allow`() {
        assertTrue(MAX_POD_VIEW_PAGE_ROWS.toLong() * MAX_POD_VIEW_COLUMNS <= MAX_POD_VIEW_PAGE_CELLS)
    }

    @Test
    fun `values are doubles with gaps kept as null and nothing but the artifact rows`() {
        val page = values(service = "orders")

        assertEquals("ieee754-double", page.str("numeric_encoding"))
        assertEquals(sha, page.str("pod_view_sha256"))
        assertEquals("orders", page.str("service"))
        val row =
            page
                .getValue("rows")
                .jsonArray
                .first()
                .jsonObject
        assertEquals(setOf("id", "pod", "container", "metric", "unit", "aggregation", "values"), row.keys)
        assertEquals("orders-1", row.str("pod"))
        assertEquals(JsonNull, row.getValue("container"))
        assertEquals(
            listOf(JsonPrimitive(1.5), JsonPrimitive(0.25), JsonNull, JsonPrimitive(3.0)),
            row.getValue("values").jsonArray.toList(),
        )
        assertEquals(
            setOf("schema_version", "pod_view_sha256", "numeric_encoding", "service", "grid", "rows", "next_after"),
            page.keys,
        )
    }

    private fun values(
        service: String = "cart",
        metric: String? = null,
        from: Long? = null,
        to: Long? = null,
        limit: Int = 64,
        after: String? = null,
        maxCells: Int = MAX_POD_VIEW_PAGE_CELLS,
    ): JsonObject = podViewValuesJson(view, sha, service, metric, from, to, limit, after, maxCells)

    private fun assertCursorRejected(
        service: String = "cart",
        metric: String? = null,
        after: String,
    ) {
        val failure = assertThrows(PodViewQueryException::class.java) { values(service = service, metric = metric, after = after) }
        assertEquals(PodViewQueryException.Kind.INVALID_CURSOR, failure.kind)
    }

    private fun JsonObject.ids(): List<String> = getValue("rows").jsonArray.map { it.jsonObject.str("id") }

    private fun JsonObject.str(name: String): String = getValue(name).jsonPrimitive.content
}

internal const val START = 1_000_000L
internal const val STEP = 60_000L

/**
 * A two-service pod-view.v1 for the read API tests (slice P2c): cart has two pods and a sidecar, orders has one pod.
 * Canonical row order is r1..r7, the first value of r1 and every pod-level orders row is checked by the tests.
 */
internal fun podViewReadFixture(arm: String? = "A"): String {
    val armField = if (arm == null) "" else """"arm":"$arm","""

    fun row(
        id: String,
        pod: String,
        container: String?,
        metric: String,
        values: String,
    ) = """{"id":"$id","pod":"$pod","container":${container?.let { "\"$it\"" } ?: "null"},"metric":"$metric","unit":"ratio",""" +
        """"aggregation":"interval_mean","values":[$values]}"""
    val cpu = "container_cpu_ratio"
    val memory = "container_memory_ratio"
    val rows =
        listOf(
            row("r1", "cart-1", "app", cpu, "0.5,0.25,null,0.75"),
            row("r2", "cart-1", "app", memory, "0.1,0.2,0.3,0.4"),
            row("r3", "cart-1", "proxy", memory, "0.01,0.02,0.03,0.04"),
            row("r4", "cart-2", "app", cpu, "null,null,null,null"),
            row("r5", "cart-2", "app", memory, "0.6,0.7,0.8,0.9"),
            row("r6", "orders-1", null, cpu, "1.5,0.25,null,3"),
            row("r7", "orders-1", null, memory, "0.2,0.2,0.2,0.2"),
        ).joinToString(",")
    return """{"schema_version":"pod-view.v1","load_input_sha256":"${"a".repeat(
        64,
    )}","resource_snapshot_sha256":"${"b".repeat(64)}",$armField""" +
        """"start_epoch_ms":$START,"step_ms":$STEP,"column_count":4,""" +
        """"coverage":{"pods_observed_total":3,"pods_included":3,"rows_observed_total":7,"rows_included":7,"selection":{"kind":"ALL"}},""" +
        """"pods":[""" +
        """{"pod":"orders-1","service":"orders","containers":[{"name":"app","role":"app"}]},""" +
        """{"pod":"cart-2","service":"cart","containers":[{"name":"app","role":"app"}]},""" +
        """{"pod":"cart-1","service":"cart","containers":[{"name":"proxy","role":"sidecar"},{"name":"app","role":"app"}]}],""" +
        """"rows":[$rows]}"""
}
