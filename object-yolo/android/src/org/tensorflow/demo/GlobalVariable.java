package org.tensorflow.demo;

import android.app.Application;

public class GlobalVariable extends Application {
    private float middlePointX;
    private float middlePointY;

    public float getMiddlePointX() {
        return middlePointX;
    }

    public float getMiddlePointY() {
        return middlePointY;
    }

    public void setMiddlePointX(float middlePointX) {
        this.middlePointX = middlePointX;
    }

    public void setMiddlePointY(float middlePointY) {
        this.middlePointY = middlePointY;
    }
}
