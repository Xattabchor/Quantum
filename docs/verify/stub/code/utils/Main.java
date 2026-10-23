package code.utils;

/**
 * Заглушка code.utils.Main ТОЛЬКО для прогона загрузчика QCT на обычной JRE
 * (docs/verify/test_qct.py): настоящая Main наследует MIDlet и её статическая
 * инициализация на JRE падает, а qctToRawImage читает Main.pixelsQ.
 * На устройстве используется настоящая Main; заглушка в сборку не попадает.
 */
public class Main {
    public static int pixelsQ = 2;      // без даунсайза, как по умолчанию
    public static boolean mipMapping = false;
}
