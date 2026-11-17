package com.example.myhelper.orchestration;

/** 报告一个根运行从受理到真实收尾的本地生命周期。 */
public interface RunActivityPort {
    Occupation occupy();

    interface Occupation extends AutoCloseable {
        @Override
        void close();
    }

    static RunActivityPort disabled() {
        return Unobserved.INSTANCE;
    }

    /** 独立装配编排器时可省略活动观测。 */
    enum Unobserved implements RunActivityPort, Occupation {
        INSTANCE;

        @Override
        public Occupation occupy() {
            return this;
        }

        @Override
        public void close() {}
    }
}
