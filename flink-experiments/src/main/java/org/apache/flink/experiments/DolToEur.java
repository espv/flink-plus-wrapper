package org.apache.flink.experiments;

import org.apache.flink.table.functions.ScalarFunction;

public class DolToEur extends ScalarFunction {
    public double eval(double price) {
        return price * 0.89;
    }
}
