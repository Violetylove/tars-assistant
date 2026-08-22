package org.atovio.tars;

interface IInputService {
    boolean swipe(int x1, int y1, int x2, int y2, int durationMs);
    boolean typeText(String text);
    void destroy();
}
