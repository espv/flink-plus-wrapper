package org.apache.flink.experiments;

import org.apache.flink.table.functions.ScalarFunction;

public class Uuid extends ScalarFunction {
    static RandomString rs32 = new RandomString(32);
    public String eval() {
        return rs32.nextString();
    }
}
