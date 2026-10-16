package code.Rendering.Meshes;

import code.Math.Matrix;
import code.Rendering.DirectX7;
import code.Rendering.Vertex;
import code.utils.DeviceProfile;


/**
 *
 * @author DDDENISSS
 */
public class BoundingBox {
    
    // Вершины принадлежат экземпляру и заполняются один раз в конструкторе:
    // minx..maxz после загрузки не меняются, а set() по 8 вершинам (24 записи
    // в поля) выполнялся на каждый вызов isVisible()/reSort(), то есть на
    // каждый NPC каждый кадр.
    private final Vertex[] vertices = new Vertex[] {
            new Vertex(0, 0, 0),
            new Vertex(0, 0, 0),
            new Vertex(0, 0, 0),
            new Vertex(0, 0, 0),
            
            new Vertex(0, 0, 0),
            new Vertex(0, 0, 0),
            new Vertex(0, 0, 0),
            new Vertex(0, 0, 0)
        };
    public int minx, miny, maxx, maxy, minz, maxz;
    private static int minpx, minpy, maxpx, maxpy, minpz, maxpz;
    // кэш для maybeVisible(): центр, полуразмеры и признак пригодности
    private int cx, cy, cz, l1, l1x, l1y, l1z;
    private boolean cullOk = false;
    
    public BoundingBox(Mesh mesh) {
        minx = mesh.minX();
        miny = mesh.minY();
        minz = mesh.minZ();
        maxx = mesh.maxX();
        maxy = mesh.maxY();
        maxz = mesh.maxZ();
        set(minx, miny, minz, maxx, maxy, maxz);
    }

    public BoundingBox(Morphing anim) {
        minx = Integer.MAX_VALUE;
        miny = Integer.MAX_VALUE;
        minz = Integer.MAX_VALUE;
        
        maxx = Integer.MIN_VALUE;
        maxy = Integer.MIN_VALUE;
        maxz = Integer.MIN_VALUE;
        
        short[][] vertices = anim.getVertices();
        for (int j = 0; j < vertices.length; j++) {
            short[] vers = vertices[j];
            for (int i = 0; i < vers.length/3; i++) {
                int x = vers[i*3];
                int y = vers[i*3+1];
                int z = vers[i*3+2];
                
                minx = Math.min(minx, x);
                miny = Math.min(miny, y);
                minz = Math.min(minz, z);
                
                maxx = Math.max(maxx, x);
                maxy = Math.max(maxy, y);
                maxz = Math.max(maxz, z);
            }
        }
        set(minx, miny, minz, maxx, maxy, maxz);
    }
    
    public void set(int minX, int minY, int minZ,
                    int maxX, int maxY, int maxZ) {
        // Центр и полуразмеры бокса для быстрого отсека (maybeVisible).
        // Отклонение любого из восьми углов от центра по каждой оси не
        // превосходит соответствующего полуразмера, поэтому интервальная
        // оценка transformed-бокса считается без обхода углов.
        cullOk = maxX >= minX && maxY >= minY && maxZ >= minZ;
        if(cullOk) {
            cx = (minX + maxX) >> 1;
            cy = (minY + maxY) >> 1;
            cz = (minZ + maxZ) >> 1;
            l1x = (maxX - minX) >> 1;
            l1y = (maxY - minY) >> 1;
            l1z = (maxZ - minZ) >> 1;
            l1 = l1x + l1y + l1z;
            // слишком большой бокс не влезает в целочисленные границы отсека
            if(l1 > (1 << 20)) cullOk = false;
        }
        vertices[0].set(minX, minY, minZ);
        vertices[1].set(maxX, minY, minZ);
        vertices[2].set(minX, minY, maxZ);
        vertices[3].set(maxX, minY, maxZ);
            
        vertices[4].set(minX, maxY, minZ);
        vertices[5].set(maxX, maxY, minZ);
        vertices[6].set(minX, maxY, maxZ);
        vertices[7].set(maxX, maxY, maxZ);
    }
    
    public boolean isVisible(DirectX7 g3d, int x1, int y1, int x2, int y2) {
        return isVisible(g3d,g3d.invCamera,x1,y1,x2,y2);
    }
    
    public boolean isVisible(DirectX7 g3d, Matrix matrix, int x1, int y1, int x2, int y2) {
        // ARM9: полный тест - это 8 преобразований вершин и 16 целочисленных
        // делений (idiv на ARM9 - библиотечная подпрограмма) на каждый объект
        // каждый кадр. Предварительная проверка центра с радиусом стоит одно
        // преобразование и два деления и отбрасывает заведомо невидимое,
        // ничего не меняя для видимого: она возвращает false только тогда,
        // когда полный тест тоже вернул бы false (см. docs/verify/test_cull.py).
        if(DeviceProfile.fastCull && !maybeVisible(g3d, matrix, x1, y1, x2, y2)) return false;

        g3d.transformAndProjectVertices( vertices, matrix );
        
        Vertex v = vertices[0];
        int minX,maxX;
        minX = maxX = v.sx;
        int minY,maxY;
        minY = maxY = v.sy;
        int minZ,maxZ;
        minZ = maxZ = v.rz;
        for (int i = 1; i < vertices.length; i++) {
            v = vertices[i];
            if(v.sx < minX) minX = v.sx;
            if(v.sy < minY) minY = v.sy;
            if(v.rz < minZ) minZ = v.rz;
            if(v.rz > maxZ) maxZ = v.rz;
            
            if(v.sx > maxX) maxX = v.sx;
            if(v.sy > maxY) maxY = v.sy;
        }
        return !( maxX < x1 || 
                  minX > x2 || 
                  maxY < y1 || 
                  minY > y2 || 
                  minZ >= 0 ||
                  -maxZ>DirectX7.drDist);
    }
    
    /**
     * Консервативная проверка "может быть виден". false означает, что все
     * восемь углов бокса гарантированно вне области отрисовки, то есть полный
     * тест isVisible() тоже вернул бы false.
     *
     * Все оценки направлены в сторону "не отсекать": радиус бокса умножается
     * на наибольшее по модулю значение строки матрицы, а знаменатель проекции
     * берётся для ближайшей к камере точки (максимальное увеличение). Если
     * хоть что-то выходит за безопасные целочисленные границы - возвращаем
     * true и объект проверяется полным тестом.
     */
    private boolean maybeVisible(DirectX7 g3d, Matrix matrix, int x1, int y1, int x2, int y2) {
        if(!cullOk) return true;
        // Пустая/перевёрнутая область отрисовки: у полного теста условия
        // maxY < y1 и minY > y2 в таком случае не образуют проверки
        // "внутри прямоугольника", поэтому эти объекты не трогаем.
        if(x1 > x2 || y1 > y2) return true;

        final int fp = Matrix.fp - 2;    // как в DirectX7.transformAndProjectVertices
        final int m00 = matrix.m00 >> 2;
        final int m01 = matrix.m01 >> 2;
        final int m02 = matrix.m02 >> 2;
        final int m10 = matrix.m10 >> 2;
        final int m11 = matrix.m11 >> 2;
        final int m12 = matrix.m12 >> 2;
        final int m20 = matrix.m20 >> 2;
        final int m21 = matrix.m21 >> 2;
        final int m22 = matrix.m22 >> 2;
        final int x = cx, y = cy, z = cz;

        // Интервальная оценка отклонения любой вершины бокса от его центра в
        // пространстве камеры:  |dSx| <= |m00|*hx + |m01|*hy + |m02|*hz,
        // где hx/hy/hz - полуразмеры, округлённые ВВЕРХ до 1/16. Каждое
        // слагаемое тоже округляется вверх, поэтому оценка не занижается.
        final int hx = (l1x + 15) >> 4;
        final int hy = (l1y + 15) >> 4;
        final int hz = (l1z + 15) >> 4;

        final int a00 = m00 < 0 ? -m00 : m00;
        final int a01 = m01 < 0 ? -m01 : m01;
        final int a02 = m02 < 0 ? -m02 : m02;
        final int a10 = m10 < 0 ? -m10 : m10;
        final int a11 = m11 < 0 ? -m11 : m11;
        final int a12 = m12 < 0 ? -m12 : m12;
        final int a20 = m20 < 0 ? -m20 : m20;
        final int a21 = m21 < 0 ? -m21 : m21;
        final int a22 = m22 < 0 ? -m22 : m22;

        final int rx = max3(a00, a01, a02);
        final int ry = max3(a10, a11, a12);
        final int rzm = max3(a20, a21, a22);
        // у матрицы поворота rx ~ Matrix.FP>>2 == 4096; больше - значит масштаб
        if(rx > 8192 || ry > 8192 || rzm > 8192) return true;

        // x*m00 обязан оставаться в int, иначе оценка радиуса не оценка
        int cmax = max3(x < 0 ? -x : x, y < 0 ? -y : y, z < 0 ? -z : z) + l1;
        if(cmax < 0) return true;
        int rmax = max3(rx, ry, rzm);
        int shift = rmax <= 1024 ? 3 : (rmax <= 2048 ? 2 : (rmax <= 4096 ? 1 : 0));
        if(cmax > ((1 << 17) << shift)) return true;

        final int radX = ((a00 * hx + 255) >> 8) + ((a01 * hy + 255) >> 8) + ((a02 * hz + 255) >> 8);
        final int radY = ((a10 * hx + 255) >> 8) + ((a11 * hy + 255) >> 8) + ((a12 * hz + 255) >> 8);
        final int radZ = ((a20 * hx + 255) >> 8) + ((a21 * hy + 255) >> 8) + ((a22 * hz + 255) >> 8);

        // центр бокса в пространстве камеры - та же формула, что и для вершин
        int sx = matrix.m03 + (x * m00 >> fp) + (y * m01 >> fp) + (z * m02 >> fp);
        int sy = -matrix.m13 - (x * m10 >> fp) - (y * m11 >> fp) - (z * m12 >> fp);
        int rz = matrix.m23 + (x * m20 >> fp) + (y * m21 >> fp) + (z * m22 >> fp);

        // все углы за камерой (полный тест: minZ >= 0)
        if(rz - radZ >= 0) return false;
        // все углы дальше расстояния отрисовки (полный тест: -maxZ > drDist)
        if(rz + radZ < -DirectX7.drDist) return false;

        // знаменатель проекции для ближайшей к камере точки бокса: там
        // увеличение максимально, значит оценка экранных границ не занижена
        int dzX = g3d.distX - rz - radZ;
        int dzY = g3d.distY - rz - radZ;
        if(dzX <= 0 || dzY <= 0) return true;    // бокс пересекает плоскость камеры

        int ax = sx < 0 ? -sx : sx;
        int ay = sy < 0 ? -sy : sy;
        int nx = ax + radX;
        int ny = ay + radY;
        if(nx < 0 || ny < 0) return true;                    // переполнение
        if((nx >> 8) * g3d.distX > 8000000) return true;     // защита nx*distX
        if((ny >> 8) * g3d.distY > 8000000) return true;

        int half = nx * g3d.distX / dzX;
        if(half > (1 << 24)) return true;                    // занимает весь экран
        if(g3d.centreX + half < x1) return false;
        if(g3d.centreX - half > x2) return false;

        half = ny * g3d.distY / dzY;
        if(half > (1 << 24)) return true;
        if(g3d.centreY + half < y1) return false;
        if(g3d.centreY - half > y2) return false;

        return true;
    }

    /** max(a,b,c) без аллокаций. */
    private static int max3(int a, int b, int c) {
        if(a < 0) a = -a;
        if(b < 0) b = -b;
        if(c < 0) c = -c;
        return a > b ? (a > c ? a : c) : (b > c ? b : c);
    }

    public void reSort(Matrix matrix) {
        DirectX7.transformSave( vertices, matrix );
        
        Vertex v = vertices[0];
        minpx = maxpx = v.x;
        minpy = maxpy = v.y;
        minpz = maxpz = v.z;
        for (int i = 1; i < vertices.length; i++) {
            v = vertices[i];
            if(v.x < minpx) minpx = v.x;
            if(v.y < minpy) minpy = v.y;
            if(v.z < minpz) minpz = v.z;
            
            if(v.x > maxpx) maxpx = v.x;
            if(v.y > maxpy) maxpy = v.y;
            if(v.z > maxpz) maxpz = v.z;
        }
    }

    public int getMinX() {
        return minpx;
    }

    public int getMinY() {
        return minpy;
    }

    public int getMinZ() {
        return minpz;
    }
    
    public int getMaxZ() {
        return maxpz;
    }

    public int getMaxX() {
        return maxpx;
    }

    public int getMaxY() {
        return maxpy;
    }
    
    
}
