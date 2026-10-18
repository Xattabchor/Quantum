package code.Rendering;

import java.lang.reflect.InvocationTargetException;

/** Activate diagnostic hooks while running existing rendering contracts. */
public final class FrameProfileSuiteRunner {
    public static void main(String[] args) throws Throwable {
        FrameProfile.begin(-1);
        FrameProfile.push(FrameProfile.SUBMIT);
        try {
            Class.forName(args[0]).getMethod("main",String[].class).invoke(null,(Object)new String[0]);
        } catch(InvocationTargetException failure) {
            throw failure.getCause();
        } finally {
            FrameProfile.pop();FrameProfile.end();
        }
        System.out.println("PASS active frame profiler preserves contracts and balanced nesting: "+args[0]);
    }
}
