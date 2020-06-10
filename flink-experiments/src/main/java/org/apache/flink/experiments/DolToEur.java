package org.apache.flink.experiments;

import org.apache.flink.table.functions.ScalarFunction;

public class DolToEur extends ScalarFunction {
    public long eval(long price) {
        return (long) (price * 0.89);
    }
}
