package com.medix.memory;

import java.util.Locale;
import org.springframework.stereotype.Service;

@Service
public class EmbeddingService {
    public static final int DIMENSIONS = 384;

    public double[] embed(String text) {
        double[] vector = new double[DIMENSIONS];
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT);
        for (int i = 0; i < normalized.length(); i++) {
            int bucket = Math.floorMod(normalized.charAt(i) * 31 + i, DIMENSIONS);
            vector[bucket] += 1.0;
        }
        normalize(vector);
        return vector;
    }

    public String pgVectorLiteral(double[] vector) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(String.format(Locale.ROOT, "%.6f", vector[i]));
        }
        return builder.append(']').toString();
    }

    private void normalize(double[] vector) {
        double sum = 0.0;
        for (double value : vector) {
            sum += value * value;
        }
        if (sum == 0.0) {
            return;
        }
        double length = Math.sqrt(sum);
        for (int i = 0; i < vector.length; i++) {
            vector[i] = vector[i] / length;
        }
    }
}
