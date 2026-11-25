package io.legado.app.ui.book.read

/** 页末位置在还有下一页时归到下一页。 */
internal fun contentSearchPageIndex(pageLengths: List<Int>, position: Int): Int {
    var pageIndex = 0
    var end = pageLengths.firstOrNull() ?: return 0
    while (end <= position && pageIndex + 1 < pageLengths.size) {
        end += pageLengths[++pageIndex]
    }
    return pageIndex
}
