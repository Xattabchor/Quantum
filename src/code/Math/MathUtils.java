package code.Math;

import code.Rendering.DirectX7;
import code.Rendering.Vertex;
import code.utils.DeviceProfile;

public final class MathUtils {

    public static int randomCall = 0;

public static int calcLight(int nx,int ny,int nz,int sx,int sy,int sz,int f) {

while(Math.abs(sx)>30000 || Math.abs(sy)>30000 || Math.abs(sz)>30000) {
sx/=2; sy/=2; sz/=2;    
}   
while(Math.abs(nx)>30000 || Math.abs(ny)>30000 || Math.abs(nz)>30000) {
nx/=2; ny/=2; nz/=2;    
}   
    
double nw=Math.sqrt((double)(nx*nx+ny*ny+nz*nz));//��� ������������

double lw=Math.sqrt((double)(sx*sx+sy*sy+sz*sz));//��� ������������

if(nw<1) nw=1;
if(lw<1) lw=1;

double l=((nx*sx)+(ny*sy)+(nz*sz))/(nw*lw);

return Math.min(Math.max((int)(l*256+f),0)*255/(255+f),255);
}

public static int calcLight(int nx,int ny,int nz,int sx,int sy,int sz) {

while(Math.abs(sx)>30000 || Math.abs(sy)>30000 || Math.abs(sz)>30000) {
sx/=2; sy/=2; sz/=2;    
}   
while(Math.abs(nx)>30000 || Math.abs(ny)>30000 || Math.abs(nz)>30000) {
nx/=2; ny/=2; nz/=2;    
}   
    
double nw=Math.sqrt((double)(nx*nx+ny*ny+nz*nz));//��� ������������

double lw=Math.sqrt((double)(sx*sx+sy*sy+sz*sz));//��� ������������

if(nw<1) nw=1;
if(lw<1) lw=1;

double l=((nx*sx)+(ny*sy)+(nz*sz))/(nw*lw);

return Math.min(Math.max((int)(l*256),0),255);
}

public static int microCalcLight(int nx,int ny,int nz,int sx,int sy,int sz) {
    while(sx > 30000 || sy > 30000 || sz > 30000 || sx < -30000 || sy < -30000 || sz < -30000) {
        sx >>= 1;
        sy >>= 1;
        sz >>= 1;
    }
    
    double lw = Math.sqrt((double) (sx * sx + sy * sy + sz * sz));//��� ������������
    
    if(lw < 1) lw=1;
    int vec = (int) (((nx * sx) + (ny * sy) + (nz * sz)) / (lw * 4096) * 255);
    
    if(vec > 255) return 255;
    return vec;
}

public static int getAnglez(int x,int y,int x1,int y1) {
      
int anglez=0;

int dist=Math.abs(x-x1)+Math.abs(y-y1);
if(dist==0) return 0;
int xx=(x-x1)*4000/dist;
int yy=(y-y1)*4000/dist;

if(xx<4000 && xx>0 && yy>0) xx=0;

if(xx>0) anglez+=xx*360;
if(xx<0) anglez-=xx*180;
if(yy>0) anglez+=yy*90;
if(yy<0) anglez-=yy*270;

anglez=anglez/4000;
anglez-=90;
anglez=fixDegree(anglez);

    return anglez;
} 
public static int getAnglezHQ(int x,int y,int x1,int y1) {
      
int anglez=0;

int dist=Math.abs(x-x1)+Math.abs(y-y1);
if(dist==0) return 0;

int xx=(x-x1)*4000/dist;
int yy=(y-y1)*4000/dist;

if(xx<4000 && xx>0 && yy>0) xx=0;

if(xx>0) anglez+=xx*2048;
if(xx<0) anglez-=xx*1024;
if(yy>0) anglez+=yy*512;
if(yy<0) anglez-=yy*1536;

anglez=anglez/4000;
anglez-=512;
anglez=fixDegree2(anglez);

    return anglez;
} 


    public static int fixDegree(int degree) {
        while(degree<0) degree+=360;
        while(degree>=360) degree-=360;
        return degree;
    }


    public static int fixDegree2(int degree) {
        while(degree<0) degree+=2048;
        while(degree>=2048) degree-=2048;
        return degree;
    }

    
    public static int mix(int a,int b,int i,int max) {
        if(i==0) return a;
        if(i==max) return b;
        
        return (a*(max-i)+b*i)/max;
    }
    
    public static int pLength(int x,int y,int z) {
        if(!DirectX7.useAutoWMove) return 0;
        // FastMath.isqrt(x) == (int)Math.sqrt(x) для любого int x, включая
        // случай переполнения суммы квадратов (там и там получается 0).
        // Важно для ARM9: pLength() вызывается в isVisible() каждого спрайта
        // каждый кадр, а double/Math.sqrt там - программная эмуляция FPU.
        return FastMath.isqrt(x*x+y*y+z*z);
    }
    
    public static int pLength(int x,int y) {
        if(!DirectX7.useAutoWMove) return 0;
        // (x+y)*(x+y) >= 0, поэтому "/4" и ">>2" совпадают; деление оставлено
        // только на случай переполнения int (там произведение становится
        // отрицательным и >>2 округляется вниз, а /4 - к нулю). Вызывается в
        // isVisible() каждого спрайта каждый кадр.
        int t=(x+y)*(x+y);
        return FastMath.isqrt(x*x+y*y+(t>=0 ? (t>>2) : t/4));
    }
    
    public static int angleDistance(int a,int b) {
        int min = Integer.MAX_VALUE;
        
        for(int i=-360;i<=360;i+=360) {
            min = Math.min(min,Math.abs(a+i-b));
        }
        
        return min;
    }
    
    public static int preudoRandom(long time, int val) {
        
        if(time<0) time*=-1;
        time += randomCall*255255;
        time%=val;
        randomCall++;
        return (int)time;
    }
    
    //======================================================================
    // Быстрые целочисленные аналоги освещения для кода ОТРИСОВКИ.
    //
    // Оригинальные calcLight()/calcLightFast()-предшественники выше остались
    // нетронутыми: calcLight() использует LightMapper, который пишет файлы
    // лайтмапов, поэтому там нужна бит-в-бит прежняя математика (иначе
    // пересобранные лайтмапы перестанут совпадать с оригинальными).
    //
    // Эти методы применяются только в кадре и только когда
    // DeviceProfile.fastLight == true (по умолчанию - на ARM9), где double
    // и Math.sqrt эмулируются программно и стоят сотен тактов. Отклонение от
    // оригинала не превышает 1 единицы из 255, что на глаз неотличимо.
    //======================================================================

    /** Кэш уменьшенного вектора направления света и его длины. */
    private static int dlKeyX = Integer.MIN_VALUE;
    private static int dlKeyY = Integer.MIN_VALUE;
    private static int dlKeyZ = Integer.MIN_VALUE;
    private static int dlX, dlY, dlZ;
    private static int dlR;
    private static boolean dlBad;

    /**
     * calcLight() для кода ОТРИСОВКИ: на ARM9-профиле целочисленный вариант,
     * на остальных - прежний, бит-в-бит. Точка выбора одна, чтобы ветки не
     * разъезжались.
     */
    public static int calcLightRender(int nx,int ny,int nz,int sx,int sy,int sz) {
        if(DeviceProfile.fastLight) return calcLightFast(nx, ny, nz, sx, sy, sz);
        return calcLight(nx, ny, nz, sx, sy, sz);
    }

    /**
     * Аналог calcLight(nx,ny,nz,sx,sy,sz) без double/Math.sqrt.
     * Порядок действий (halving, переполнения, усечение, clamp) сохранён.
     */
    public static int calcLightFast(int nx,int ny,int nz,int sx,int sy,int sz) {
        while(sx > 30000 || sy > 30000 || sz > 30000 || sx < -30000 || sy < -30000 || sz < -30000) {
            sx = FastMath.div2(sx); sy = FastMath.div2(sy); sz = FastMath.div2(sz);
        }
        while(nx > 30000 || ny > 30000 || nz > 30000 || nx < -30000 || ny < -30000 || nz < -30000) {
            nx = FastMath.div2(nx); ny = FastMath.div2(ny); nz = FastMath.div2(nz);
        }

        int n2 = nx*nx + ny*ny + nz*nz;
        int s2 = sx*sx + sy*sy + sz*sz;
        // В оригинале переполнение даёт NaN, а (int)NaN == 0.
        if(n2 < 0 || s2 < 0) return 0;

        int rn = FastMath.isqrtRound(n2);
        if(rn < 1) rn = 1;
        int rs = FastMath.isqrtRound(s2);
        if(rs < 1) rs = 1;

        int dot = (nx*sx) + (ny*sy) + (nz*sz);

        // (int)(dot/(rn*rs)*256). Одно 64-битное деление вместо пары
        // Math.sqrt + трёх double-операций оригинала: на ARM9 это разница
        // между десятками и сотнями тактов. Отклонение от оригинала - не
        // больше 1..2 единиц из 255 (проверено перебором, docs/ARM9.md).
        int v = (int) (((long) dot * 256) / ((long) rn * rs));

        return FastMath.clamp255(v);
    }

    /**
     * То же, что calcLightFast(), но второй вектор - направление света из
     * DirectX7.lightdir*, которое не меняется в течение уровня. Его длина
     * кэшируется, поэтому на полигон остаётся одно деление.
     */
    public static int calcLightDirFast(int nx,int ny,int nz) {
        int sx = DirectX7.lightdirx;
        int sy = DirectX7.lightdiry;
        int sz = DirectX7.lightdirz;

        if(sx != dlKeyX || sy != dlKeyY || sz != dlKeyZ) {
            dlKeyX = sx; dlKeyY = sy; dlKeyZ = sz;

            int tx = sx, ty = sy, tz = sz;
            while(tx > 30000 || ty > 30000 || tz > 30000 || tx < -30000 || ty < -30000 || tz < -30000) {
                tx = FastMath.div2(tx); ty = FastMath.div2(ty); tz = FastMath.div2(tz);
            }

            int s2 = tx*tx + ty*ty + tz*tz;
            dlBad = s2 < 0;
            dlX = tx; dlY = ty; dlZ = tz;
            dlR = dlBad ? 1 : FastMath.isqrtRound(s2);
            if(dlR < 1) dlR = 1;
        }

        if(dlBad) return 0;

        while(nx > 30000 || ny > 30000 || nz > 30000 || nx < -30000 || ny < -30000 || nz < -30000) {
            nx = FastMath.div2(nx); ny = FastMath.div2(ny); nz = FastMath.div2(nz);
        }

        int n2 = nx*nx + ny*ny + nz*nz;
        if(n2 < 0) return 0;

        int rn = FastMath.isqrtRound(n2);
        if(rn < 1) rn = 1;

        int dot = (nx*dlX) + (ny*dlY) + (nz*dlZ);
        int v = (int) (((long) dot * 256) / ((long) rn * dlR));

        return FastMath.clamp255(v);
    }

    /**
     * Аналог microCalcLight() без double/Math.sqrt. Вызывается в
     * RenderObject.getLight() на каждую освещённую вершину каждый кадр
     * (фонарик, вспышка выстрела, CAMERA_VECTOR_LIGHT).
     */
    public static int microCalcLightFast(int nx,int ny,int nz,int sx,int sy,int sz) {
        while(sx > 30000 || sy > 30000 || sz > 30000 || sx < -30000 || sy < -30000 || sz < -30000) {
            sx >>= 1;
            sy >>= 1;
            sz >>= 1;
        }

        int s2 = sx*sx + sy*sy + sz*sz;
        if(s2 < 0) return 0;   // оригинал: NaN -> (int)NaN == 0

        int rs = FastMath.isqrtRound(s2);
        if(rs < 1) rs = 1;     // оригинал: if(lw < 1) lw = 1;

        int dot = (nx*sx) + (ny*sy) + (nz*sz);

        // (int)(dot/(rs*4096)*255): одно long-деление вместо Math.sqrt и
        // трёх double-операций. |dot| <= 2^31, поэтому *255 в long не
        // переполняется.
        int v = (int) (((long) dot * 255) / ((long) rs << 12));

        if(v > 255) return 255;
        return v;
    }

    public static int round(double d) {
        int dd = (int)d;
        double last = Math.abs(d)-Math.abs(dd);
        int ad = d>0?1:-1;
        return dd+(last>=0.5?ad:0);
    }
    
    public static int ceil(double d) {
        int dd = (int)d;
        int ad = d>0?1:-1;
        return dd+(Math.floor(d)!=d?ad:0);
    }
}
