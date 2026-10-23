import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import code.Rendering.RawImage;
import code.utils.QctEncoder;

/**
 * Кросс-проверка: байты QCT, положенные в classpath как ресурс, читает
 * НАСТОЯЩИЙ загрузчик движка RawImage.createRawImage() (тот самый
 * qctToRawImage), и результат обязан побитово совпасть с QctEncoder.decode().
 * Запускается docs/verify/test_qct.py после TestQctEncode.
 */
public class TestQctLoad {

    public static void main(String[] args) throws Exception {
        String[] names = new String[]{"t0", "t1", "t2", "t3"};
        int bad = 0;
        for(int i = 0; i < names.length; i++) {
            String res = "/" + names[i] + ".qct";
            byte[] b = read(res);
            if(b == null) {
                System.out.println("LOAD " + names[i] + " match=0 (нет ресурса)");
                bad++;
                continue;
            }
            int[] dec = QctEncoder.decode(b);
            RawImage ri = RawImage.createRawImage(res);
            boolean ok = ri != null && ri.img != null
                    && ri.img.length == dec.length;
            if(ok) {
                for(int k = 0; k < dec.length; k++) {
                    if(ri.img[k] != dec[k]) {
                        ok = false;
                        break;
                    }
                }
            }
            System.out.println("LOAD " + names[i] + " match=" + (ok ? 1 : 0)
                    + " w=" + (ri != null ? ri.w : -1)
                    + " h=" + (ri != null ? ri.h : -1));
            if(!ok) bad++;
        }
        System.out.println(bad == 0 ? "ALL LOAD OK" : ("LOAD FAILURES " + bad));
        System.exit(bad == 0 ? 0 : 1);
    }

    static byte[] read(String res) throws Exception {
        InputStream is = TestQctLoad.class.getResourceAsStream(res);
        if(is == null) return null;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int c;
        while((c = is.read()) != -1) bos.write(c);
        is.close();
        return bos.toByteArray();
    }
}
