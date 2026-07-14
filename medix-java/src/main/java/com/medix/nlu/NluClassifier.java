package com.medix.nlu;

@FunctionalInterface
public interface NluClassifier {
    NluResult classify(String text);
}
