package org.tensorflow.demo;

import android.app.Application;
// 전역변수를 저장하는 클래스
public class GlobalVariable extends Application {
    private float middlePointX;
    private float middlePointY;
    private float distance;
    private String label;

    public float getMiddlePointX() {
        return middlePointX;
    }

    public float getMiddlePointY() {
        return middlePointY;
    }

    public float getDistance() {
        return distance;
    }

    public String getLabel() {
        return label;
    }

    public void setMiddlePointX(float middlePointX) {
        this.middlePointX = middlePointX;
    }

    public void setMiddlePointY(float middlePointY) {
        this.middlePointY = middlePointY;
    }

    public void setDistance(float distance) {
        this.distance = distance;
    }

    public void setLabel(String label) {
        this.label = label;
    }
}
