package code.utils;

/**
 * Профиль устройства: определяет класс CPU и включает соответствующие
 * ветки рендера.
 *
 * Основной адресат - аппараты на ARM9 (ARM926EJ-S / ARM946E-S): Sony
 * Ericsson DB20xx, Samsung SGH/GT, Nokia Series40, Motorola, LG, Siemens,
 * Philips и им подобные. Их особенности:
 *  - нет аппаратного делителя (idiv/irem/ldiv - библиотечные подпрограммы);
 *  - нет FPU (double/float и Math.sqrt - программная эмуляция);
 *  - маленький heap (0.5..2 Мб), поэтому любой мусор в кадре = пауза GC;
 *  - байткод обычно интерпретируется, то есть цена кода ~ числу байткодов.
 *
 * Профиль НИКОГДА не меняет формат файлов: ни уровней, ни текстур, ни
 * setting.txt, ни записей RMS. Все новые ключи setting.txt опциональны и
 * имеют значения по умолчанию, совпадающие с прежним поведением, поэтому
 * оригинальные файлы продолжают работать как раньше.
 *
 * Ключи setting.txt (секция без группы, все опциональны):
 *   CPU_PROFILE=AUTO|ARM9|FAST   - принудительный выбор профиля
 *   FAST_LIGHT=0|1               - целочисленное освещение вместо double
 *   PERSPECTIVE_STEP=0..256      - шаг перспективной коррекции (0 = как раньше)
 *   NEAR_ONLY=0|1                - симулировать/столкновлять только объекты
 *                                  рядом с игроком (Main.updateOnlyNear*)
 *   AI_LOD=0|1                   - далёким ботам искать цель вдвое реже
 *   AI_LOD_DIST=<единиц карты>   - порог "далёкости" для AI_LOD (по умолч. 10000)
 *   MORPH_LOD=0|1                - дальним мешам не смешивать кадры анимации
 *   MORPH_LOD_DIST=<единиц>      - порог "далёкости" для MORPH_LOD (по умолч. 12000)
 *   FAST_CULL=0|1                - быстрый отсек BoundingBox (центр+радиус
 *                                  до полного теста по 8 углам)
 *   QCT=0|1                      - подхватывать упакованные текстуры .qct,
 *                                  лежащие рядом с .png (по умолчанию 1)
 *   FAST_SETUP=0|1               - подготовка полигонов без idiv: коэффициенты
 *                                  тумана через магическое умножение, +/-1 из
 *                                  255 (по умолчанию 1 на ARM9, иначе 0)
 *
 * Ключи POSTFX и ARM9_QUALITY вырезаны по запросу автора и безопасно
 * игнорируются: постэффекты отключает сам профиль ARM9 (postFx = !lowEnd),
 * а качество (displaySize/туман/текстуры/перспектива) настраивается в меню
 * игры, дефолты первого запуска больше не меняются.
 *
 * @author Quantum engine (ARM9 optimization pack)
 */
public final class DeviceProfile {

    public static final int CPU_AUTO = 0;
    public static final int CPU_ARM9 = 1;
    public static final int CPU_FAST = 2;

    /** Определённый класс CPU. */
    public static int cpu = CPU_AUTO;
    /** ARM9-класс: всё, что без FPU/делителя и с маленьким heap. */
    public static boolean lowEnd = false;
    /** Целочисленное освещение вместо double (см. MathUtils.*Fast). */
    public static boolean fastLight = false;
    /** Разрешены ли дорогие постэффекты кадра (bloom, виньетка).
     *  Ключ POSTFX вырезан: значение определяет только профиль -
     *  ARM9 отключает их, FAST/AUTO оставляют как в оригинале. */
    public static boolean postFx = true;
    /** >0 - переопределить Main.q (шаг перспективной коррекции в пикселах). */
    public static int perspectiveStep = 0;
    /** Обновлять только объекты рядом с игроком (значения по умолчанию для
     *  UPDATE_ONLY_NEAR / UPDATE_ONLY_NEAR_PHYSICS в LevelLoader). */
    public static boolean nearOnly = false;
    /** AI-LOD: далёким ботам поиск цели выполняется вдвое реже. */
    public static boolean aiLod = false;
    /** Квадрат порога "далёкости" для aiLod, в единицах карты. */
    public static int aiLodDist = 10000 * 10000;
    /** Дальним MeshImage не интерполировать кадры анимации (Morphing). */
    public static boolean morphLod = false;
    /** Дистанция (в единицах карты), дальше которой меш считается дальним. */
    public static int morphLodDist = 12000;
    /** BoundingBox.isVisible(): сначала дешёвая проверка центра с радиусом. */
    public static boolean fastCull = false;
    /** Загружать .qct рядом с .png, если упаковщик положил его в JAR. */
    public static boolean qct = true;

    /** FAST_SETUP: коэффициенты тумана в подготовке полигонов без idiv (+/-1/255). */
    public static boolean fastSetup = false;

    public static String platform = "";
    public static String osName = null;
    public static boolean desktop = false;
    public static boolean symbian = false;

    private DeviceProfile() {
    }

    /** Короткое имя профиля - для отладочного HUD/Benchmark. */
    public static String getName() {
        if(cpu == CPU_ARM9) return "ARM9";
        if(cpu == CPU_FAST) return "FAST";
        return "AUTO";
    }

    public static final boolean isArm9() {
        return cpu == CPU_ARM9;
    }

    /**
     * Определяет профиль. Вызывается один раз из Main.init().
     *
     * @param settings setting.txt (может быть null)
     */
    public static void init(GameIni settings) {
        platform = getProperty("microedition.platform", "");
        osName = getProperty("os.name", null);
        String config = getProperty("microedition.configuration", null);

        desktop = isDesktop();
        symbian = isSymbian();

        int forced = CPU_AUTO;
        int fastLightOpt = -1;
        int nearOnlyOpt = -1;
        int aiLodOpt = -1;
        int morphLodOpt = -1;
        int fastCullOpt = -1;
        int qctOpt = -1;
        int fastSetupOpt = -1;

        if(settings != null) {
            String prof = settings.getNoLang("CPU_PROFILE");
            if(prof != null) {
                prof = trim(prof);
                if(equalsIgnoreCase(prof, "ARM9") || prof.equals("1")) forced = CPU_ARM9;
                else if(equalsIgnoreCase(prof, "FAST") || prof.equals("2")) forced = CPU_FAST;
                else forced = CPU_AUTO;
            }
            fastLightOpt = settings.getIntNoLang("FAST_LIGHT", -1);
            nearOnlyOpt = settings.getIntNoLang("NEAR_ONLY", -1);
            aiLodOpt = settings.getIntNoLang("AI_LOD", -1);
            morphLodOpt = settings.getIntNoLang("MORPH_LOD", -1);
            fastCullOpt = settings.getIntNoLang("FAST_CULL", -1);
            qctOpt = settings.getIntNoLang("QCT", -1);
            fastSetupOpt = settings.getIntNoLang("FAST_SETUP", -1);
            morphLodDist = settings.getIntNoLang("MORPH_LOD_DIST", morphLodDist);
            if(morphLodDist < 0) morphLodDist = 0;
            if(morphLodDist > 1000000) morphLodDist = 1000000;   // без переполнения << 2
            int aiLodDistOpt = settings.getIntNoLang("AI_LOD_DIST", 0);
            if(aiLodDistOpt > 0) {
                if(aiLodDistOpt > 46000) aiLodDistOpt = 46000;   // без переполнения квадрата
                aiLodDist = aiLodDistOpt * aiLodDistOpt;
            }
            perspectiveStep = settings.getIntNoLang("PERSPECTIVE_STEP", 0);
            if(perspectiveStep < 0) perspectiveStep = 0;
            if(perspectiveStep > 256) perspectiveStep = 256;
        }

        cpu = forced != CPU_AUTO ? forced : detect(config);

        lowEnd = cpu == CPU_ARM9;

        // Значения по умолчанию: на ARM9 убираем double из освещения и
        // отключаем самые дорогие постэффекты кадра. Явный ключ в
        // setting.txt всегда важнее.
        fastLight = fastLightOpt >= 0 ? (fastLightOpt == 1) : lowEnd;
        postFx = !lowEnd;   // ключ POSTFX вырезан: решает только профиль
        nearOnly = nearOnlyOpt >= 0 ? (nearOnlyOpt == 1) : lowEnd;
        aiLod = aiLodOpt >= 0 ? (aiLodOpt == 1) : lowEnd;
        morphLod = morphLodOpt >= 0 ? (morphLodOpt == 1) : lowEnd;
        fastCull = fastCullOpt >= 0 ? (fastCullOpt == 1) : lowEnd;
        // QCT не зависит от профиля: это формат хранения, а не ухудшение.
        qct = qctOpt >= 0 ? (qctOpt == 1) : true;
        // FAST_SETUP: градации тумана +/-1 из 255, зато подготовка без idiv.
        fastSetup = fastSetupOpt >= 0 ? (fastSetupOpt == 1) : lowEnd;

        if(lowEnd && perspectiveStep == 0 && settings != null
                && settings.getIntNoLang("PERSPECTIVE_STEP", -1) < 0) {
            // На ARM9 шаг коррекции чуть крупнее: реже выполняется
            // 64-битное деление в перспективном растеризаторе.
            perspectiveStep = 32;
        }

        System.out.println("DeviceProfile: cpu=" + getName()
                + " platform=" + platform
                + " fastLight=" + (fastLight ? 1 : 0)
                + " postFx=" + (postFx ? 1 : 0)
                + " nearOnly=" + (nearOnly ? 1 : 0)
                + " aiLod=" + (aiLod ? 1 : 0)
                + " morphLod=" + (morphLod ? 1 : 0)
                + " fastSetup=" + (fastSetup ? 1 : 0)
                + " fastCull=" + (fastCull ? 1 : 0)
                + " persStep=" + perspectiveStep);
    }

    /** Автоопределение класса CPU. */
    private static int detect(String config) {
        if(desktop) return CPU_FAST;
        if(symbian) return CPU_FAST;

        String p = platform;

        // Явные признаки "быстрой" платформы.
        if(p.indexOf("pstros") >= 0 || p.indexOf("KEmulator") >= 0
                || p.indexOf("FreeJ2ME") >= 0 || p.indexOf("J2ME Loader") >= 0
                || p.indexOf("android") >= 0) {
            return CPU_FAST;
        }

        // UIQ/Symbian аппараты Sony Ericsson, которые не попали в список S60.
        if(starts(p, "SonyEricssonP") || starts(p, "SonyEricssonM600")
                || starts(p, "SonyEricssonW950") || starts(p, "SonyEricssonG700")
                || starts(p, "SonyEricssonXperia")) {
            return CPU_FAST;
        }

        // ARM9-семейства J2ME-телефонов.
        if(starts(p, "SonyEricsson") || starts(p, "SEMCCP") || starts(p, "SEMC")
                || starts(p, "Samsung") || starts(p, "SAMSUNG")
                || starts(p, "Motorola") || starts(p, "MOT-") || starts(p, "MOTO")
                || starts(p, "LG-") || starts(p, "LG/") || starts(p, "LGE")
                || starts(p, "Fly") || starts(p, "Philips") || starts(p, "SAGEM")
                || starts(p, "Sagem") || starts(p, "BenQ") || starts(p, "SIEMENS")
                || starts(p, "Siemens") || starts(p, "Panasonic")
                || starts(p, "SHARP") || starts(p, "Toshiba") || starts(p, "Alcatel")
                || starts(p, "ZTE") || starts(p, "Huawei") || starts(p, "Nexian")) {
            return CPU_ARM9;
        }

        // Nokia: S60 уже отсеян выше, остальное - Series40 (ARM9/ARM11 без FPU).
        if(starts(p, "Nokia")) {
            return CPU_ARM9;
        }

        // CLDC 1.0 почти всегда означает старый медленный аппарат.
        if(config != null && config.indexOf("CLDC-1.0") >= 0) {
            return CPU_ARM9;
        }

        return CPU_AUTO;
    }

    private static boolean starts(String s, String prefix) {
        return s.startsWith(prefix);
    }

    private static boolean isDesktop() {
        if(osName == null) return false;
        String os = osName.toLowerCase();
        return os.indexOf("win") > -1 || os.indexOf("mac") > -1
                || os.indexOf("nix") > -1 || os.indexOf("nux") > -1
                || os.indexOf("aix") > -1 || os.indexOf("android") > -1;
    }

    /**
     * Тот же набор S60-аппаратов, что использует Main.defaultSettings().
     * Вынесено сюда, чтобы определение профиля и настройка качества не
     * разъезжались.
     */
    private static boolean isSymbian() {
        if(osName != null) {
            return osName.toLowerCase().equals("symbian");
        }
        String[] devicesS60 = new String[]{
            "NokiaE71", "NokiaE66", "NokiaE63", "Nokia6124", "NokiaN95", "NokiaN82",
            "NokiaN81", "NokiaN76", "NokiaE90", "NokiaE51", "Nokia6290", "Nokia6121",
            "Nokia6110", "Nokia5700", "NokiaN77", "NokiaE65", "NokiaE61", "NokiaN93",
            "NokiaN92", "NokiaN91", "NokiaN80", "NokiaN75", "NokiaN73", "NokiaN71",
            "NokiaE70", "NokiaE62", "NokiaE60", "NokiaE50", "Nokia3250", "NokiaN91",
            "NokiaN72", "NokiaN90", "NokiaN70", "Nokia6681", "Nokia6680", "Nokia6630",
            "NokiaN96", "NokiaN85", "NokiaN79", "NokiaN78", "NokiaN93i", "NokiaE75",
            "NokiaE72", "NokiaE52", "NokiaE55", "NokiaX6", "Nokia5800", "Nokia5530",
            "Nokia5230", "NokiaC6", "NokiaC5", "NokiaN8", "NokiaE7", "Nokia500"
        };

        for(int i = 0; i < devicesS60.length; i++) {
            if(platform.indexOf(devicesS60[i]) > -1) return true;
        }
        return false;
    }

    private static String getProperty(String key, String def) {
        try {
            String value = System.getProperty(key);
            return value == null ? def : value;
        } catch (Throwable t) {
            // На некоторых аппаратах System.getProperty бросает исключение
            // для неизвестных ключей - профиль не должен из-за этого падать.
            return def;
        }
    }

    private static boolean equalsIgnoreCase(String a, String b) {
        return a.equals(b) || a.toLowerCase().equals(b.toLowerCase());
    }

    private static String trim(String s) {
        int begin = 0;
        int end = s.length();
        while(begin < end && s.charAt(begin) <= ' ') begin++;
        while(end > begin && s.charAt(end - 1) <= ' ') end--;
        return begin == 0 && end == s.length() ? s : s.substring(begin, end);
    }
}
