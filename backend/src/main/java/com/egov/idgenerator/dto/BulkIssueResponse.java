package com.egov.idgenerator.dto;

import java.util.List;

public record BulkIssueResponse(int count, List<String> ids) {
}
