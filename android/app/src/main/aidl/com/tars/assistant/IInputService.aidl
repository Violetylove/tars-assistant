package com.tars.assistant;

interface IInputService {
    boolean swipe(int x1, int y1, int x2, int y2, int durationMs);
    void destroy();
}
