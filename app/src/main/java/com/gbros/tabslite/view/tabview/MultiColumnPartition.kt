package com.gbros.tabslite.view.tabview

internal data class MultiColumnPartition<T>(
    val columns: List<List<T>>,
    val fixedColumnsHeight: Int
)

/** Fills every fixed column up to [columnHeight], splitting blocks only at supplied line endings. */
internal fun <T> partitionMultiColumn(
    blocks: List<T>,
    columnCount: Int,
    columnHeight: Int,
    heightOf: (T) -> Int,
    lineEnds: (T) -> List<Int>,
    splitAt: (T, Int) -> Pair<T, T>
): MultiColumnPartition<T> {
    require(columnCount >= 2)
    val columns = List(columnCount) { mutableListOf<T>() }
    var nextBlockIndex = 0
    var pendingBlock: T? = null
    var fixedColumnsHeight = 0

    for (columnIndex in 0 until columnCount - 1) {
        val currentColumn = columns[columnIndex]
        var currentHeight = 0
        while (true) {
            val block = pendingBlock ?: blocks.getOrNull(nextBlockIndex)?.also { nextBlockIndex++ } ?: break
            val blockHeight = heightOf(block)
            if (currentColumn.isEmpty() || currentHeight + blockHeight <= columnHeight) {
                currentColumn.add(block)
                currentHeight += blockHeight
                pendingBlock = null
                continue
            }

            val split = lineEnds(block).asReversed().firstNotNullOfOrNull { end ->
                val (head, tail) = splitAt(block, end)
                val headHeight = heightOf(head)
                if (currentHeight + headHeight <= columnHeight) Triple(head, headHeight, tail) else null
            }
            if (split != null) {
                val (head, headHeight, tail) = split
                currentColumn.add(head)
                currentHeight += headHeight
                pendingBlock = tail
            } else {
                pendingBlock = block
            }
            break
        }
        fixedColumnsHeight += minOf(currentHeight, columnHeight)
        if (pendingBlock == null && nextBlockIndex >= blocks.size) break
    }

    pendingBlock?.let { columns.last().add(it) }
    if (nextBlockIndex < blocks.size) {
        columns.last().addAll(blocks.subList(nextBlockIndex, blocks.size))
    }
    return MultiColumnPartition(columns, fixedColumnsHeight)
}
