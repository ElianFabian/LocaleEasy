package com.elianfabian.localeeasy.lint;

import com.android.tools.lint.client.api.IssueRegistry;
import com.android.tools.lint.client.api.Vendor;
import com.android.tools.lint.detector.api.ApiKt;
import com.android.tools.lint.detector.api.Issue;
import java.util.List;

public final class LocaleEasyIssueRegistry extends IssueRegistry {
    @Override
    public List<Issue> getIssues() {
        return List.of(ContentViewRewriteDetector.ISSUE,
                InstrumentationDetector.ACTIVITY_CONTEXT, InstrumentationDetector.WEB_VIEW_CONSTRUCTION,
                InstrumentationDetector.LAYOUT_INFLATION, InstrumentationDetector.WEB_VIEW_LOAD,
                InstrumentationDetector.FRAGMENT_LAYOUT);
    }

    @Override
    public int getApi() {
        return ApiKt.CURRENT_API;
    }

    @Override
    public int getMinApi() {
        return ApiKt.CURRENT_API;
    }

    @Override
    public Vendor getVendor() {
        return new Vendor("LocaleEasy", "com.elianfabian.localeeasy", "https://github.com/ElianFabian/LocaleEasy/issues");
    }
}
