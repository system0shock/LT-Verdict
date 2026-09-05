package io.ltverdict.sources

import io.ltverdict.core.AnalysisOutcome
import io.ltverdict.core.AnalysisRequest
import io.ltverdict.core.AnalysisService

internal fun analyzeWithSources(
    service: AnalysisService,
    request: AnalysisRequest,
    source: PromqlSource?,
    processedBytes: (Long) -> Unit = {},
    checkCancelled: () -> Unit = {},
): AnalysisOutcome {
    val selection = request.sourceRequest ?: return service.analyze(request, processedBytes, checkCancelled)
    require(request.resources == null && request.diagnostics == null && request.sourceAcquisition == null) { "SOURCE_INPUT_CONFLICT" }
    val acquisition = requireNotNull(source) { "SOURCE_NOT_CONFIGURED" }.acquire(selection, request.input.sha256, checkCancelled)
    return service.analyze(
        request.copy(sourceRequest = null, resources = acquisition.snapshot, sourceAcquisition = acquisition),
        processedBytes,
        checkCancelled,
    )
}
