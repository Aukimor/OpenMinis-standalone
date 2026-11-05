package com.openminis.app.virtualdisplay;

import android.view.Surface;

interface IBackgroundDisplayService {
    int createDisplay(in Surface surface, int width, int height, int dpi) = 1;
    void releaseDisplay(int displayId) = 2;
    int startApp(int displayId, String packageName, int userId, boolean force) = 3;
    int topTaskId(int displayId) = 4;
    String topActivity(int displayId) = 5;
    boolean moveTaskToDisplay(int taskId, int displayId) = 6;
    boolean injectTap(int displayId, int x, int y) = 7;
    boolean injectSwipe(int displayId, int x1, int y1, int x2, int y2, int durationMs) = 8;
    boolean injectKey(int displayId, int keyCode) = 9;
    boolean setAudioMuted(String packageName, boolean muted) = 10;
    void settleBehind(int displayId) = 11;
    void releaseAll() = 12;
    void destroy() = 16777114;
}
