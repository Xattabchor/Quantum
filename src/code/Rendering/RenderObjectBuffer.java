package code.Rendering;

import code.Rendering.Meshes.Polygon3V;
import code.Rendering.Meshes.Polygon4V;

/**
 *
 * @author DDDENISSS
 */
public class RenderObjectBuffer {
    
    private TMPElement[] buffer = new TMPElement[0];
    private int size = 0;

    public RenderObjectBuffer() {
    }
    
    public void addRenderObjects(final RenderObject[] objects, final Texture tex, final int x1, final int y1, final int x2, final int y2) {
        final int count = objects.length;
        for(int i=0; i<count; i++) {
            RenderObject obj = objects[i];
            if(obj.isVisible(x1, y1, x2, y2)) {
                if(size >= buffer.length) increaseBuffer();
                TMPElement el = buffer[size];
                el.obj = obj;
                el.tex = tex;
                size++;
            }
        }
    }
    
    public void addRenderObjects(final RenderObject[] objects, final int x1, final int y1, final int x2, final int y2) {
        final int count = objects.length;
        for(int i=0; i<count; i++) {
            RenderObject obj = objects[i];
            if(obj.isVisible(x1, y1, x2, y2)) {
                if(size >= buffer.length) increaseBuffer();
                TMPElement el = buffer[size];
                el.obj = obj;
                el.tex = null;
                size++;
            }
        }
    }

    public void addRenderObjects(final RenderObject[] objects, final MultyTexture tex, final int x1, final int y1, final int x2, final int y2) {
        final int count = objects.length;
        for(int i=0; i<count; i++) {
            RenderObject obj = objects[i];
            if(obj.isVisible(x1, y1, x2, y2)) {
                if(size >= buffer.length) increaseBuffer();
                TMPElement el = buffer[size];
                el.obj = obj;
                el.tex = tex.textures[0];

if(obj.kind == RenderObject.KIND_POLY4) {
    el.tex=tex.textures[((Polygon4V)(obj)).tex];
} else if(obj.kind == RenderObject.KIND_POLY3) {
el.tex=tex.textures[((Polygon3V)(obj)).tex];
}
                size++;
            }
        }
    }


    public void addRenderObject(final TMPElement obj, final int x1, final int y1, final int x2, final int y2) {
            RenderObject objz = obj.obj;
            if(objz.isVisible(x1, y1, x2, y2)) {
                if(size >= buffer.length) increaseBuffer();
                TMPElement el = buffer[size];
                el.obj=obj.obj;
                el.tex=obj.tex;
                size++;
            }
        
    }
    
    public void addRenderObject(final RenderObject obj, final Texture tex, final int x1, final int y1, final int x2, final int y2) {
        if( obj.isVisible(x1, y1, x2, y2) ) {
            if(size >= buffer.length) increaseBuffer();
            TMPElement el = buffer[size];
            el.obj = obj;
            el.tex = tex;
            size++;
        }
    }

    public void addRenderObjectDT(final RenderObject obj, final Texture tex, final int x1, final int y1, final int x2, final int y2) {
            if(size >= buffer.length) increaseBuffer();
            TMPElement el = buffer[size];
            el.obj = obj;
            el.tex = tex;
            size++;
        
    }

    public void addRenderObject(final RenderObject obj, final MultyTexture tex, final int x1, final int y1, final int x2, final int y2) {
        if( obj.isVisible(x1, y1, x2, y2) ) {
            if(size >= buffer.length) increaseBuffer();
            TMPElement el = buffer[size];
            el.obj = obj;
            el.tex = tex.textures[0];
if(obj.kind == RenderObject.KIND_POLY4) {
el.tex=tex.textures[((Polygon4V)(obj)).tex];
} else if(obj.kind == RenderObject.KIND_POLY3) {
el.tex=tex.textures[((Polygon3V)(obj)).tex];
}

            size++;
        }
    }


    public void addRenderObject(final RenderObject obj, final int x1, final int y1, final int x2, final int y2) {
        if( obj.isVisible(x1, y1, x2, y2) ) {
            if(size >= buffer.length) increaseBuffer();
            TMPElement el = buffer[size];
            el.obj = obj;
            el.tex = null;

            size++;
        }
    }
    
    private void increaseBuffer() {
        final int increase = 50;
        TMPElement[] newBuffer = new TMPElement[buffer.length+increase];
        System.arraycopy(buffer, 0, newBuffer, 0, buffer.length);
        for (int i = buffer.length; i < newBuffer.length; i++) {
            newBuffer[i] = new TMPElement();
        }
        buffer = newBuffer;
    }
    
    /**
     * Сортировка по убыванию sz (отрисовка идёт с конца списка).
     *
     * Между кадрами порядок полигонов одного меша почти не меняется, поэтому
     * короткие и почти упорядоченные диапазоны досортировываются вставками -
     * это O(N + число инверсий) вместо O(N log N) с рекурсией и обменами.
     * Для больших перемешанных диапазонов (глобальный буфер DirectX7)
     * проверка cheap: смотрим только первые 32 пары и сразу уходим в
     * прежнюю быструю сортировку.
     */
    public void sort(int start, int end) {
        if(start >= end) return;

        // Между кадрами порядок полигонов одного меша почти не меняется,
        // поэтому сначала пробуем досортировать вставками: для почти
        // упорядоченного массива это O(N + сумма смещений) вместо
        // O(N log N). Вставки сохраняют элементы с равным sz в
        // порядке добавления (сравнение строгое "<"), и fallback тоже
        // стабилен - значит порядок равных всегда одинаков и не зависит
        // от того, какой путь сработал в этом кадре.
        if(insertionSortLimited(start, end, end - start <= 24 ? Integer.MAX_VALUE : (end - start + 1) * 2)) {
            return;
        }

        mergeSortStable(start, end);
    }

    private TMPElement[] mergeScratch = null;

    /**
     * Устойчивая восходящая сортировка слияниями по убыванию sz (без рекурсии).
     *
     * Fallback ОБЯЗАН быть устойчивым: z-буфера в движке нет, и для
     * компланарных/равноудалённых полигонов (sz = минимальный rz вершин,
     * на сеточных уровнях равенства массовые - например, дверь лежит в
     * одной плоскости со стеной) видимость определяет ТОЛЬКО порядок в
     * буфере: отрисовка идёт с конца, поздний полигон перекрывает ранний.
     * Прежняя неустойчивая быстрая сортировка на каждом кадре давала свой
     * порядок равных элементов, а после частично отработавших вставок
     * порядок ещё и зависел от глубины прерывания - из-за этого порядок
     * равных полигонов менялся между кадрами и [MESH]-объекты "мерцали"
     * (кадр рисуются за стеной, кадр - перед ней). Теперь и вставки, и
     * слияния устойчивы: порядок равных = порядок добавления, постоянен
     * между кадрами; объекты добавляются после геометрии комнаты, поэтому
     * компланарный [MESH] стабильно рисуется поверх стены.
     */
    private void mergeSortStable(final int start, final int end) {
        final int n = end - start + 1;
        TMPElement[] tmp = mergeScratch;
        if(tmp == null || tmp.length < n) {
            tmp = new TMPElement[n];
            mergeScratch = tmp;
        }
        System.arraycopy(buffer, start, tmp, 0, n);
        boolean srcIsTmp = true;   // где сейчас данные: true - в tmp, false - в buffer[start..]
        for(int width = 1; width < n; width <<= 1) {
            final int step = width << 1;
            for(int lo = 0; lo < n; lo += step) {
                int mid = lo + width; if(mid > n) mid = n;
                int hi  = lo + step;  if(hi  > n) hi  = n;
                int i = lo, j = mid, k = lo;
                if(srcIsTmp) {
                    // слияние tmp -> buffer; при равенстве sz берём левый прогон (устойчиво)
                    while(i < mid && j < hi) {
                        if(tmp[j].obj.sz > tmp[i].obj.sz) buffer[start + k++] = tmp[j++];
                        else buffer[start + k++] = tmp[i++];
                    }
                    while(i < mid) buffer[start + k++] = tmp[i++];
                    while(j < hi)  buffer[start + k++] = tmp[j++];
                } else {
                    // слияние buffer -> tmp; при равенстве sz берём левый прогон (устойчиво)
                    while(i < mid && j < hi) {
                        if(buffer[start + j].obj.sz > buffer[start + i].obj.sz) tmp[k++] = buffer[start + j++];
                        else tmp[k++] = buffer[start + i++];
                    }
                    while(i < mid) tmp[k++] = buffer[start + i++];
                    while(j < hi)  tmp[k++] = buffer[start + j++];
                }
            }
            srcIsTmp = !srcIsTmp;
        }
        if(srcIsTmp) {   // чётное число прогонов (или ноль) - результат в tmp
            System.arraycopy(tmp, 0, buffer, start, n);
        }
    }

    /**
     * Сортировка вставками по убыванию sz с ограничением на число перестановок.
     *
     * @return true, если диапазон отсортирован полностью; false - если бюджет
     *         исчерпан (массив при этом остаётся корректной перестановкой).
     */
    private boolean insertionSortLimited(int start, int end, int budget) {
        int work = 0;
        for(int i = start + 1; i <= end; i++) {
            final TMPElement v = buffer[i];
            final int vsz = v.obj.sz;
            int j = i - 1;
            while(j >= start && buffer[j].obj.sz < vsz) {
                buffer[j+1] = buffer[j];
                j--;
                if(++work > budget) {
                    buffer[j+1] = v;
                    return false;
                }
            }
            buffer[j+1] = v;
        }
        return true;
    }
    
    public void reset() {
        size = 0;
    }

    public void resetTex() {
        for(int i=0;i<buffer.length;i++) {
((TMPElement)buffer[i]).tex=null;
}
    }
    


    public TMPElement[] getBuffer() {
        return buffer;
    }

    public int getSize() {
        return size;
    }
    
}


