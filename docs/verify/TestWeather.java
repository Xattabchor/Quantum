import code.utils.WeatherGenerator;

/**
 * Инварианты целочисленной погоды (int Q8 вместо float):
 *  - 400 кадров смешанных moveX/moveX2/moveY/moveY2/move/moveB/update;
 *  - типы частиц только 0/1/2, глубина 0..100 после update;
 *  - координаты остаются в разумных границах (нет переполнений/наноподобного
 *    дрейфа), Q8-масштаб соблюдён;
 *  - частицы действительно двигаются и перерождаются.
 *
 * Компилируется ECJ против движка (source/target 1.4), выполняется на JRE.
 * Запускается из docs/verify/test_setup_math.py. "WEATHER OK" = зелёный.
 */
public class TestWeather {

    static int fails = 0;

    static void check(boolean ok, String msg) {
        if(!ok) {
            fails++;
            if(fails <= 20) System.out.println("FAIL: " + msg);
        }
    }

    public static void main(String[] args) {
        int W = 240, H = 320;
        WeatherGenerator wg = new WeatherGenerator(W, H, 100,
                0x99aacc, 0x556688, 0xffffff, 0xaaaaaa,
                false, null, 6, 8, W / 2, H / 2, 3, 4);

        check(wg.coords.length == 200, "размер буфера " + wg.coords.length);

        wg.generate = 1;
        wg.createParticles();
        for(int i = 0; i < wg.coords.length; i++) {
            check(wg.coords[i] >= 0 && wg.coords[i] <= (H << 8),
                  "createParticles Q8 " + wg.coords[i]);
        }

        long motion = 0;
        int deaths = 0;
        int prevX0 = wg.coords[0];

        for(int f = 0; f < 400; f++) {
            int ft = 20 + (f % 7) * 15;

            wg.moveX((f & 1) == 0 ? 5 : -5, ft);
            wg.moveX2((f & 2) == 0 ? 3 : -3, ft);
            wg.moveY(4, ft);
            wg.moveY2(6, ft);
            if((f % 37) == 0) wg.move(5, ft, (f % 3) - 1);
            if((f % 53) == 0) wg.moveB(5, ft, (f % 3) - 1);
            if((f % 97) == 0) {
                wg.generate = (byte) (wg.generate == 1 ? 2 : 1);
                wg.createParticles();
            }

            wg.update(ft, (f % 5) - 2, (f % 11) == 0);

            for(int i = 0; i < wg.coords.length; i += 2) {
                int x = wg.coords[i], y = wg.coords[i + 1];
                int t = wg.info[i], d = wg.info[i + 1];
                check(t == 0 || t == 1 || t == 2, "тип " + t);
                check(d >= 0 && d <= 100, "глубина " + d);
                check(x >= -((W + 64) << 8) && x <= ((2 * W + 64) << 8),
                      "граница x " + x);
                check(y >= -((H + 64) << 8) && y <= ((2 * H + 64) << 8),
                      "граница y " + y);
                if(t != 0) motion += (x & 15) + (y & 15);
            }

            if(wg.info[0] == 0) deaths++;
        }

        check(motion > 0, "частицы не двигаются");
        check(wg.coords[0] != prevX0 || deaths > 0, "нет движения/смертей");

        // снег падает: детерминированная проверка одной частицей.
        // Остальные частицы погашены (info=0), underRoof=true запрещает их
        // перерождение, глубина 10 (<40) выводит частицу из-под kill-порога -
        // y обязан строго расти каждый кадр.
        WeatherGenerator w2 = new WeatherGenerator(W, H, 100,
                0, 0, 0xffffff, 0xaaaaaa, false, null, 6, 8,
                W / 2, H / 2, 3, 4);
        w2.generate = 2;
        w2.createParticles();
        for(int i = 1; i < 100; i++) w2.info[i * 2] = 0;
        w2.info[0] = 2;
        w2.info[1] = 10;
        w2.coords[0] = 120 << 8;
        w2.coords[1] = 10 << 8;
        int yPrev = w2.coords[1];
        boolean monotonic = true;
        for(int f = 0; f < 60; f++) {
            w2.update(30, 0, true);
            if(w2.coords[1] <= yPrev) monotonic = false;
            yPrev = w2.coords[1];
        }
        check(monotonic, "снег не падает: y не растёт строго каждый кадр");
        check(yPrev > (10 << 8) + 60 * 256, "снег падает слишком медленно: "
                + (yPrev - (10 << 8)) / 256 + " px за 60 кадров");
        check(w2.info[0] == 2, "частица снега умерла без причины");

        System.out.println("fails=" + fails + " motion=" + motion
                + " deaths=" + deaths);
        if(fails == 0) System.out.println("WEATHER OK");
    }
}
