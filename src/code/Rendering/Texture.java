package code.Rendering;

import code.Math.FastMath;
import code.utils.Asset;
import code.utils.FrameClock;

public class Texture {

    public boolean perspectiveCorrection;
    public RawImage rImg;
    public ByteRawImage brImg;
    public RawImage[] mip = null;
    public RawImage[] brMip = null;
    public byte drawmode = DirectX7.standartDrawmode;
    public int addsz = 0;
    public boolean castShadow = true;
    public boolean collision = true;

    public Texture[] animMIP;
    public float animation_speed = 1.0f;

    Texture() {
    }

    public Texture(RawImage ri, boolean perspectiveCorrection, ByteRawImage bri) {
        setTexture(ri,perspectiveCorrection,bri);
    }
    
    void setTexture(RawImage ri, boolean perspectiveCorrection, ByteRawImage bri) {
        this.perspectiveCorrection = perspectiveCorrection;
        rImg = ri;
        drawmode = DirectX7.standartDrawmode;
        brImg = bri;
    }

    public int[] getPixels() {
        return this.rImg.img;
    }

    public byte getDrawMode() {
        return this.drawmode;
    }

    public void setDrawMode(byte dr) {
        drawmode = dr;
    }

    public void setPerspectiveCorrection(boolean perspectiveCorrection) {
        this.perspectiveCorrection = perspectiveCorrection;
    }

    public static Texture createTexture(String file) {
        RawImage rimg = Asset.getRawImage(file);
        return new Texture(rimg,false,null);
    }

    /**
     * Кэш периода анимации: раньше (long)(animMIP.length*1000/animation_speed)
     * пересчитывался с float-делением каждый кадр для каждой текстуры.
     * Поля без private - MultyTexture (тот же пакет) сбрасывает animPeriod,
     * когда переустанавливает animMIP.
     */
    long animPeriod = 0;
    float animPeriodSpeed = 0f;
    /** animMIP.length - 1, если длина - степень двойки, иначе 0. */
    int animLenMask = 0;

    public void updateAnimation() {
        if(animMIP == null) return;
        if(animPeriod == 0 || animation_speed != animPeriodSpeed) {
            animPeriodSpeed = animation_speed;
            animPeriod = (long) (animMIP.length * 1000 / animation_speed);
            animLenMask = FastMath.isPow2(animMIP.length) ? animMIP.length - 1 : 0;
        }
        int anim_index;
        if(animPeriod > 0L && animPeriod <= 2147483647L) {
            // Абсолютное время epoch в int не помещается, поэтому остаток
            // берётся от интервала с момента запуска (FrameClock.base): фаза
            // анимации сдвигается на постоянную величину, а период и взаимная
            // синхронность текстур не меняются. 64-битное деление (на ARM9 -
            // библиотечная подпрограмма) заменяется 32-битным, нативный вызов
            // currentTimeMillis() на каждую текстуру убирается вовсе.
            int t = FrameClock.elapsed() % (int) animPeriod;
            anim_index = (int) ((float) t * animation_speed / 1000);
        } else {
            long time = FrameClock.time() % animPeriod;
            anim_index = (int) (time * animation_speed / 1000);
        }
        // '%' на переменный делитель на ARM9 - библиотечная подпрограмма;
        // для степени двойки достаточно маски (индекс здесь неотрицательный).
        anim_index = animLenMask != 0 ? (anim_index & animLenMask) : anim_index % animMIP.length;

        rImg = animMIP[anim_index].rImg;
        mip = animMIP[anim_index].mip;
        brMip = animMIP[anim_index].brMip;
    }
}
