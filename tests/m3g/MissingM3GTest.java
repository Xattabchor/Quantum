package code.Rendering;

public final class MissingM3GTest {
    public static void main(String[] args) {
        DirectX7 g = M3GTest.renderer();
        M3GTest.submit(g, M3GTest.triangle(), M3GTest.texture(0xffffffff), 0);
        g.render();
        M3GTest.check(!g.isM3GActive(), "Missing JSR-184 did not fall back");
        M3GTest.check(g.getRendererError() != null, "Missing JSR-184 not diagnosed");
        g.destroy();
        System.out.println("PASS missing JSR-184 API at runtime");
    }
}
