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
        // O(N log N) с рекурсией и обменами. Если смещений больше бюджета,
        // метод возвращает false, и диапазон досортировывается прежней
        // быстрой сортировкой (ей всё равно, в каком порядке массив).
        if(insertionSortLimited(start, end, end - start <= 24 ? Integer.MAX_VALUE : (end - start + 1) * 2)) {
            return;
        }

        TMPElement tmp;
        int opora = buffer[(start+end)>>1].obj.sz;
        
        int first = start, second = end;
        
        while(first <= second) {
            while(buffer[first].obj.sz>opora) {first++;} //Отрисовка идёт с конца списка
            while(buffer[second].obj.sz<opora) {second--;}
            
            if(first <= second) {
                tmp = buffer[first];
                buffer[first] = buffer[second];
                buffer[second] = tmp;
                first++; second--;
            }
        }
        
        if(start < second) sort(start,second);
        if(end > first) sort(first,end);
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


