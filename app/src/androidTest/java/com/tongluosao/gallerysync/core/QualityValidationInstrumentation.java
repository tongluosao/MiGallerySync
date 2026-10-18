package com.tongluosao.gallerysync.core;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;

/** Runs the on-device synthetic-data safety scenario without JUnit or AndroidX. */
public final class QualityValidationInstrumentation extends Instrumentation {

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override
    public void onStart() {
        Bundle result = new Bundle();
        try {
            new OriginalStoreInstrumentationScenario().run(getTargetContext());
            result.putString("stream", "Synthetic original-store validation passed");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            String detail = android.util.Log.getStackTraceString(error);
            android.util.Log.e("QualityValidation", detail);
            result.putString("stream", "Synthetic original-store validation failed: " + detail);
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}
