package org.geminiassist.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;

/**
 * The "drawer" window: assistant triggers and shares open Gemini inside a
 * 92%-height bottom card that slides up, can be dragged down and is dismissed by a
 * tap outside. The Patches entry point here is the drawer header button - there is
 * no second toolbar and no floating button.
 *
 * Whether the drawer is used at all is configurable per trigger type in Patches.
 */
public class ChatActivity extends MainActivity {

    private boolean isDismissing = false;
    private int cardHeight = 0;

    private boolean shouldUseDrawer() {
        Intent intent = getIntent();
        String action = intent != null ? intent.getAction() : null;
        SharedPreferences prefs = getSharedPreferences("gemini_assist_prefs", MODE_PRIVATE);

        if (Intent.ACTION_ASSIST.equals(action)) {
            return prefs.getBoolean("use_drawer_assistant", true);
        }
        return prefs.getBoolean("use_drawer_shared", true);
    }

    @Override
    protected int getLayoutResourceId() {
        return shouldUseDrawer() ? R.layout.activity_chat : R.layout.activity_main;
    }

    @Override
    protected void setActivityTheme() {
        if (!shouldUseDrawer()) {
            super.setActivityTheme();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final View bottomDrawerCard = findViewById(R.id.bottomDrawerCard);
        final View drawerHeader = findViewById(R.id.drawerHeader);
        final View dismissArea = findViewById(R.id.dismissArea);

        // The drawer performs its own slide-up, so no system activity animation.
        overridePendingTransition(0, 0);

        if (dismissArea != null) {
            dismissArea.setOnClickListener(v -> dismissDrawer());
        }

        if (bottomDrawerCard != null) {
            bottomDrawerCard.getViewTreeObserver().addOnGlobalLayoutListener(
                    new ViewTreeObserver.OnGlobalLayoutListener() {
                        @Override
                        public void onGlobalLayout() {
                            bottomDrawerCard.getViewTreeObserver().removeOnGlobalLayoutListener(this);

                            int screenHeight = getResources().getDisplayMetrics().heightPixels;
                            cardHeight = (int) (screenHeight * 0.92);

                            android.view.ViewGroup.LayoutParams params = bottomDrawerCard.getLayoutParams();
                            if (params != null) {
                                params.height = cardHeight;
                                bottomDrawerCard.setLayoutParams(params);
                            }

                            bottomDrawerCard.setTranslationY(cardHeight);
                            bottomDrawerCard.animate()
                                    .translationY(0)
                                    .setDuration(300) // slide-up duration
                                    .start();
                        }
                    });
        }

        // Drag-to-dismiss on the header row.
        if (drawerHeader != null && bottomDrawerCard != null) {
            final float[] initialTouchY = new float[1];
            final float[] initialTranslationY = new float[1];

            drawerHeader.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    if (cardHeight <= 0) return false;

                    switch (event.getAction()) {
                        case MotionEvent.ACTION_DOWN:
                            initialTouchY[0] = event.getRawY();
                            initialTranslationY[0] = bottomDrawerCard.getTranslationY();
                            return true;

                        case MotionEvent.ACTION_MOVE:
                            float deltaY = event.getRawY() - initialTouchY[0];
                            float newTranslationY = initialTranslationY[0] + deltaY;
                            // Clamp between 0 (expanded) and cardHeight (hidden).
                            if (newTranslationY < 0) {
                                newTranslationY = 0;
                            } else if (newTranslationY > cardHeight) {
                                newTranslationY = cardHeight;
                            }
                            bottomDrawerCard.setTranslationY(newTranslationY);
                            return true;

                        case MotionEvent.ACTION_UP:
                            float currentTranslationY = bottomDrawerCard.getTranslationY();
                            // Released past 35% of the way down -> dismiss, otherwise snap back.
                            if (currentTranslationY > cardHeight * 0.35f) {
                                dismissDrawer();
                            } else {
                                bottomDrawerCard.animate()
                                        .translationY(0)
                                        .setDuration(250)
                                        .start();
                            }
                            v.performClick();
                            return true;
                    }
                    return false;
                }
            });
        }
    }

    private void dismissDrawer() {
        if (isDismissing) return;
        isDismissing = true;

        final View bottomDrawerCard = findViewById(R.id.bottomDrawerCard);
        if (bottomDrawerCard != null) {
            int targetY = cardHeight > 0 ? cardHeight : bottomDrawerCard.getHeight();
            bottomDrawerCard.animate()
                    .translationY(targetY)
                    .setDuration(250) // slide-down duration
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            ChatActivity.super.finish();
                            overridePendingTransition(0, 0);
                        }
                    })
                    .start();
        } else {
            ChatActivity.super.finish();
            overridePendingTransition(0, 0);
        }
    }

    /** Every close path (back button, swipe, tap outside) runs the slide-down first. */
    @Override
    public void finish() {
        dismissDrawer();
    }
}
