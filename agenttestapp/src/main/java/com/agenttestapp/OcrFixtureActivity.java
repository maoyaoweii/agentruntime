package com.agenttestapp;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;

/** Synthetic, drawn-only screens for the isolated emulator OCR regression. */
public final class OcrFixtureActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(new DrawnScreen());
    }

    private final class DrawnScreen extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean secondPage;

        DrawnScreen() {
            super(OcrFixtureActivity.this);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override protected void onDraw(Canvas canvas) {
            canvas.drawColor(Color.WHITE);
            float density = getResources().getDisplayMetrics().density;
            paint.setColor(Color.BLACK);
            paint.setTextSize(30 * density);
            canvas.drawText(secondPage ? "NEXT PAGE 456" : "SCREEN TEST 123", 16 * density, 90 * density, paint);
            canvas.drawText(secondPage ? "第二屏内容" : "中文识别测试", 16 * density, 150 * density, paint);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_UP) { secondPage = true; invalidate(); }
            return true;
        }
    }
}
