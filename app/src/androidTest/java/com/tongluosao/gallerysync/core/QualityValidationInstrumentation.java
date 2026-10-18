package com.tongluosao.gallerysync.core;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;

/** Runs the on-device synthetic-data safety scenario without JUnit or AndroidX. */
public final class QualityValidationInstrumentation extends Instrumentation {
    private Bundle mArguments = Bundle.EMPTY;

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        mArguments = arguments == null ? Bundle.EMPTY : new Bundle(arguments);
        start();
    }

    @Override
    public void onStart() {
        Bundle result = new Bundle();
        String probeInput = mArguments.getString("photo_quality_probe_input");
        try {
            if (probeInput != null) {
                result.putAll(PhotoQualityProbeInstrumentation.runProbe(
                        getTargetContext(), probeInput));
                finish(Activity.RESULT_OK, result);
                return;
            }

            String heicStatus = new OriginalStoreInstrumentationScenario().run(getTargetContext());
            result.putString("heic_status", heicStatus);
            if (heicStatus.startsWith("HEIC_ENCODE_VERIFIED")) {
                result.putString("stream",
                        "Synthetic original-store validation and device HEIC encoding passed");
                finish(Activity.RESULT_OK, result);
            } else {
                result.putString("stream", "Core synthetic validation passed; device HEIC encode "
                        + "was not verified: " + heicStatus);
                // Keep unsupported hardware distinct from an HEIC encoding success.
                finish(Activity.RESULT_CANCELED, result);
            }
        } catch (Throwable error) {
            String detail = android.util.Log.getStackTraceString(error);
            if (probeInput != null) {
                android.util.Log.e("PhotoQualityProbe", detail);
                result.putString("status", "FAILED");
                result.putString("failure", detail);
            } else {
                android.util.Log.e("QualityValidation", detail);
                result.putString("stream", "Synthetic original-store validation failed: "
                        + detail);
            }
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}
