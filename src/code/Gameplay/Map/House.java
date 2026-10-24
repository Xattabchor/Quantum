package code.Gameplay.Map;

import code.Collision.Height;
import code.Math.Matrix;
import code.Gameplay.Objects.MeshObject;
import code.Collision.Ray;
import code.HUD.DeveloperMenu;
import code.Rendering.DirectX7;
import code.Rendering.Texture;
import code.Math.Vector3D;
import java.util.Vector;
import javax.microedition.lcdui.Graphics;

public final class House {
    public static boolean l2dRoomRendering;
    public static boolean boxRoomTesting;

    private Room[] rooms;
    private Room[][] neighbours;
    private Vector roomsToRender = new Vector(); // ? или renderedRooms
    private final Vector renderedPortals = new Vector();
    private Skybox skybox;
    private final Vector tVec = new Vector();
    private final Vector tVec2 = new Vector();
    private final Vector tmpObj = new Vector();
    /**
     * Отдельный буфер для списка объектов, обновляемых в кадре.
     * getNearObjects() возвращает tVec, а tVec внутри кадра перезаполняется
     * физикой (sphereCast/rayCast/computeHeightFull), поэтому итерировать его
     * в Scene.recomputePart() при Main.updateOnlyNear нельзя.
     */
    private final Vector tVec3 = new Vector();

    /**
     * Номер кадра физики. Инкрементируется один раз за кадр из
     * Scene.recomputePart(); нужен, чтобы кэшировать список близких объектов:
     * раньше getNearObjects() вызывался трижды на каждого персонажа за кадр
     * (sphereCast, computeHeightFull, rayCast) и каждый раз перестраивал
     * Vector обходом комнаты и всех соседей.
     */
    public int physicsFrame = 0;
    private int nearObjPart = -2;
    private int nearObjFrame = -1;

    /**
     * Сброс кэша близких объектов: состав списка меняется при добавлении/
     * удалении объекта и при переезде объекта между комнатами, поэтому кэш
     * живёт ровно до такого события (или до следующего кадра).
     */
    private void invalidateNearObjects() {
        nearObjPart = -2;
        nearObjFrame = -1;
    }

    /** Кэш номеров "комната + соседи" (структура соседей после загрузки не меняется). */
    private int[][] nearPartsCache = new int[0][];
    private static final int[] NO_PARTS = new int[0];

    public House(Room[] rooms, Room[][] neighbours) {
        this.rooms = rooms;
        this.neighbours = neighbours;

    }

    public final void destroy() {
        for(int i = 0; i < this.rooms.length; ++i) {
            rooms[i].destroy();
            rooms[i] = null;
        }

        rooms = null;
        neighbours = null;
        roomsToRender.removeAllElements();
        renderedPortals.removeAllElements();
        
        roomsToRender = null;
        if(skybox != null) {
            skybox.destroy();
            skybox = null;
        }
        invalidateNearObjects();
        nearPartsCache = new int[0][];
        nearRoomsCache = new Room[0][];
        allObjFrame = -1;
        tVec.removeAllElements();
        tVec2.removeAllElements();
        tVec3.removeAllElements();
        tmpObj.removeAllElements();
    }

    public final void setTexture(Texture texture) {
        for(int i = 0; i < rooms.length; ++i) {
            rooms[i].getMesh().setTexture(texture);
        }
    }

    public final void setSkybox(Skybox skybox) {
        this.skybox = skybox;
    }

    public final Skybox getSkybox() {
        return skybox;
    }

    public final Room[] getRooms() {
        return rooms;
    }

    public final Room[] getNeighbourRooms(int part) {
        return neighbours[part];
    }

    private Vector getNearRooms(int part) {
        roomsToRender.removeAllElements();
        
        if(part != -1) {
            if(rooms[part] != null) roomsToRender.addElement(rooms[part]);

            Room[] nei = neighbours[part];

            for(int i=0; i<nei.length; i++) {
                if(nei[i] != null) roomsToRender.addElement(nei[i]);
            }

        }
        return roomsToRender;
    }

    /** ARM9: "комната + соседи" кэшем Room[] - getNearRooms() перестраивала
     *  Vector (removeAllElements + synchronized addElement) на каждый вызов
     *  sphereCast/rayCast, то есть по нескольку раз на персонажа за кадр,
     *  хотя структура соседей после загрузки не меняется. Порядок комнат тот
     *  же: сначала своя, затем соседи. */
    private Room[][] nearRoomsCache = new Room[0][];
    private static final Room[] NO_ROOMS = new Room[0];

    private final Room[] getNearRoomsArr(int part) {
        if(part == -1) return NO_ROOMS;
        if(part >= nearRoomsCache.length) {
            Room[][] grown = new Room[part + 8][];
            System.arraycopy(nearRoomsCache, 0, grown, 0, nearRoomsCache.length);
            nearRoomsCache = grown;
        }
        Room[] cached = nearRoomsCache[part];
        if(cached != null) return cached;
        Room[] nei = neighbours[part];
        int count = 0;
        if(rooms[part] != null) count = 1;
        for(int i = 0; i < nei.length; i++) if(nei[i] != null) count++;
        Room[] out = new Room[count];
        count = 0;
        if(rooms[part] != null) out[count++] = rooms[part];
        for(int i = 0; i < nei.length; i++) if(nei[i] != null) out[count++] = nei[i];
        nearRoomsCache[part] = out;
        return out;
    }

    public final boolean sphereCast(int part, Vector3D pos, int rad) {
        boolean col = false;

        Room[] nearRms = getNearRoomsArr(part); //Spherecast rooms
        for(int i=0; i<nearRms.length; i++) {
            col |= nearRms[i].sphereCast(pos, rad);
        }

        Vector nearObj = getNearObjectsCached(part); //Spherecast meshobjects
        if(nearObj.size() >= 1) {
            for(int i = 0; i < nearObj.size(); i++) {

                Object obj = nearObj.elementAt(i);
                if(obj instanceof MeshObject) {
                    MeshObject point = (MeshObject) obj;
                    if(point.precCol) {
                        col |= point.sphereCollisionTest(pos, rad);
                    }
                }

            }
        }

        return col;
    }

    public final boolean sphereCast(int parts[], Vector3D pos, int rad) {
        renderedPortals.removeAllElements();
        boolean col = false;

        for(int i = 0; i < parts.length; i++) { //Spherecast rooms
            int part = parts[i];
            Vector nearRms = getNearRooms(part);

            if(nearRms.size() >= 1) {
                for(int i2 = 0; i2 < nearRms.size(); i2++) {

                    Room room = (Room) nearRms.elementAt(i2);
                    if(!renderedPortals.contains(room)) {
                        col |= room.sphereCast(pos, rad);
                        renderedPortals.addElement(room);
                    }

                }
            }

        }

        Vector nearObj = getNearObjectsCached(parts[0]); //Spherecast meshobjects only in one part cause other SHOULD be nighbour
        if(nearObj.size() >= 1) {
            for(int i = 0; i < nearObj.size(); i++) {

                Object obj = nearObj.elementAt(i);
                if(obj instanceof MeshObject) {
                    MeshObject point = (MeshObject) obj;
                    if(point.precCol) {
                        col |= point.sphereCollisionTest(pos, rad);
                    }
                }

            }
        }

        return col;
    }

    public final void rayCast(int part, Ray ray, boolean rayIsFromWeapons) {
        if(part == -1) return;

        Room[] nearRms = getNearRoomsArr(part); //Raycast rooms
        for(int i = 0; i < nearRms.length; i++) {
            nearRms[i].rayCast(ray);
        }

        Vector nearObj = getNearObjectsCached(part); //Raycast meshobjects
        for(int i = 0; i < nearObj.size(); i++) {
            Object obj = nearObj.elementAt(i);

            if(obj instanceof MeshObject) {
                MeshObject point = (MeshObject) obj;
                if(point.precCol && (!rayIsFromWeapons || !point.ignoreWeaponRayCast)) {
                    point.rayCast(ray);
                }
            }
        }

    }

    public final void rayCastNoObjects(int part, Ray ray) {
        if(part == -1) return;

        Room[] nearRms = getNearRoomsArr(part); //Raycast rooms
        for(int i = 0; i < nearRms.length; i++) {
            nearRms[i].rayCast(ray);
        }
    }

    public void computeHeight(int part, Height height) {
        if(part == -1) return;
        rooms[part].computeHeight(height);

        Room[] near = neighbours[part];
        for(int i=0; i<near.length; i++) {
            if(near[i] != null) near[i].computeHeight(height);
        }
    }

    public void computeHeightFull(int part, Height height) {
        if(part == -1) return;
        computeHeight(part, height);

        Vector nearObj = getNearObjectsCached(part);
        for(int i = 0; i < nearObj.size(); i++) {
            Object obj = nearObj.elementAt(i);

            if(obj instanceof MeshObject) {
                MeshObject point = (MeshObject) obj;
                if(point.precCol) {
                    point.computeHeight(height);
                }
            }
        }
    }

    /**
     * То же, что getNearObjects(tVec, part), но результат кэшируется на кадр:
     * в пределах одного кадра состав близких объектов не меняется, а вызовов
     * на персонажа бывает три.
     */
    public final Vector getNearObjectsCached(int part) {
        if(part == nearObjPart && physicsFrame == nearObjFrame) return tVec;
        getNearObjects(tVec, part);
        nearObjPart = part;
        nearObjFrame = physicsFrame;
        return tVec;
    }

    /**
     * Номера комнаты part и её соседей. Используется вместо
     * Vector.contains() в квадратичном цикле проверки пар персонажей
     * (contains - это линейный поиск, то есть O(N^3) на кадр).
     */
    public final int[] getNearParts(int part) {
        if(part == -1 || part < 0) return NO_PARTS;
        if(part >= nearPartsCache.length) {
            int[][] grown = new int[part + 8][];
            System.arraycopy(nearPartsCache, 0, grown, 0, nearPartsCache.length);
            nearPartsCache = grown;
        }
        int[] cached = nearPartsCache[part];
        if(cached != null) return cached;

        Room[] near = neighbours[part];
        int count = 1;
        for(int i = 0; i < near.length; i++) if(near[i] != null) count++;
        int[] out = new int[count];
        out[0] = part;
        count = 1;
        for(int i = 0; i < near.length; i++) if(near[i] != null) out[count++] = near[i].getId();
        nearPartsCache[part] = out;
        return out;
    }

    public static boolean containsPart(int[] parts, int part) {
        for(int i = 0; i < parts.length; i++) if(parts[i] == part) return true;
        return false;
    }

    public final void getNearObjects(Vector buf, int part) {
        buf.removeAllElements();
        if(part == -1) return;
        
        if(rooms.length == 1) {
            Vector list2 = rooms[0].getObjects();
            
            for(int i=list2.size()-1; i>=0; i--) {
                buf.addElement(list2.elementAt(i));
            }
            
            return;
        }

        rooms[part].getObjects(buf);
        Room[] near = neighbours[part];

        for(int i = 0; i < near.length; i++) {
            if(near[i] != null) {
                near[i].getObjects(buf);
            }
        }
    }

    /**
     * Близкие объекты в отдельном буфере - для обхода в Scene.recomputePart().
     */
    public final Vector getNearObjectsForUpdate(int part) {
        getNearObjects(tVec3, part);
        return tVec3;
    }

    public final Vector getNearObjects(int part) {
        tmpObj.removeAllElements();
        if(part == -1) return tmpObj;
        
        if(rooms.length == 1) {
            Vector list2 = rooms[0].getObjects();
            
            for(int i=list2.size()-1; i>=0; i--) {
                tmpObj.addElement(list2.elementAt(i));
            }
            
            return tmpObj;
        }

        rooms[part].getObjects(tmpObj);

        Room[] near = neighbours[part];
        if(near == null) return tmpObj;

        for(int i = 0; i < near.length; i++) {
            if(near[i] != null) {
                near[i].getObjects(tmpObj);
            }
        }

        return tmpObj;
    }
    
    private final void renderSkybox(Graphics g, DirectX7 g3d) {
        if(skybox != null && (skybox.draw || skybox.skyboxAlways)) {
            skybox.render(g, g3d);
            g3d.render();
            skybox.resetViewport();
        }
    }

    public final int render(Graphics g, DirectX7 g3d, int part[], int cx, int cz) {
        renderSkybox(g,g3d);
        if(part.length == 0) return 0;
        
        roomsToRender.removeAllElements();
        renderedPortals.removeAllElements();
        
        for(int x=part.length-1; x>=0; x--) {
            if(part[x] == -1) continue;
            renderRoom(g3d, rooms[part[x]], 0, 0, g3d.getWidth(), g3d.getHeight(), cx, cz, false);
        }

        if(roomsToRender.size() > 0) {
            for(int x=0; x<roomsToRender.size(); x++) {
                Room room = (Room) roomsToRender.elementAt(x);
                room.render(g3d, cx, cz);
                room.renderObjects(g3d);
            }
        }

        return roomsToRender.size();

    }

    public final int render(Graphics g, DirectX7 g3d, int part, int cx, int cz) {
        renderSkybox(g,g3d);
        if(part == -1) return 0;

        roomsToRender.removeAllElements();
        renderedPortals.removeAllElements();
        
        renderRoom(g3d, rooms[part], 0, 0, g3d.getWidth(), g3d.getHeight(), cx, cz, false);

        for(int i=0; i<roomsToRender.size(); i++) {
            Room room = (Room) roomsToRender.elementAt(i);
            room.render(g3d, cx, cz);
            room.renderObjects(g3d);
        }

        return roomsToRender.size();
    }

    private void renderRoom(DirectX7 g3d, Room mainRoom, int x1, int y1, int x2, int y2, int cx, int cz, boolean renderRooms) {
        if(mainRoom == null) return;
        
        if(!roomsToRender.contains(mainRoom)) {
            roomsToRender.addElement(mainRoom);
            mainRoom.setViewport(x1, y1, x2, y2);
        } else mainRoom.addViewport(x1, y1, x2, y2);
        
        if(renderRooms) mainRoom.render(g3d, cx, cz);

        if(mainRoom.isOpenSky() && skybox != null) {
            skybox.addViewport(x1, y1, x2, y2);
            skybox.draw = true;
        }

        Portal[] portals = mainRoom.getPortals();

        if(portals == null) return;
        if(portals.length == 0) return;

        for(int i=0; i<portals.length; i++) {
            Portal portal = portals[i];
            if(!portal.isVisible(g3d, x1, y1, x2, y2)) continue;

            Room room = portal.getRoom();
            boolean renderedBefore = roomsToRender.contains(room);

            int pMinX = portal.getMinX();
            int pMinY = portal.getMinY();
            int pMaxX = portal.getMaxX();
            int pMaxY = portal.getMaxY();
            if(pMinX < x1) pMinX = x1;
            if(pMinY < y1) pMinY = y1;
            if(pMaxX > x2) pMaxX = x2;
            if(pMaxY > y2) pMaxY = y2;

            int sizex = pMaxX - pMinX;
            int sizey = pMaxY - pMinY;
            if(sizex <= 0 || sizey <= 0) continue;

            if(!renderedPortals.contains(portal)) renderedPortals.addElement(portal);

            if(room != null) {

                if(renderedBefore && room.viewportContains(pMinX, pMinY, pMaxX, pMaxY)) continue;

                renderRoom(g3d, room, pMinX, pMinY, pMaxX, pMaxY, cx, cz, renderRooms);

            } else if(skybox != null) {
                skybox.addViewport(pMinX, pMinY, pMaxX, pMaxY);;
                skybox.draw = true;
            }


        }

    }
    
    public void drawPortals(Graphics g, int x, int y) {
        for(int i=0; i<renderedPortals.size(); i++) {
            Portal p = (Portal) renderedPortals.elementAt(i);
            p.paint(g, x, y);
        }
        
        for(int i=0; i<roomsToRender.size(); i++) {
            Room room = (Room) roomsToRender.elementAt(i);
            room.paint(g, x, y);
        }
    }

    public final Vector getObjects() {
        tVec2.removeAllElements();
        for(int i=0; i<rooms.length; i++) {
            rooms[i].getObjects(tVec2);
        }
        return tVec2;
    }

    /** ARM9: полный список объектов, закэшированный на кадр физики.
     *  NPC/Zombie/BigZombie.action() вызывали getObjects() на каждый
     *  react-тик - а это ПОЛНАЯ перестройка Vector (обход всех комнат и
     *  synchronized addElement на каждый объект) по разу на каждого бота.
     *  Теперь перестройка максимум раз на кадр; инвалидация - по номеру
     *  кадра физики и Room.objectsRevision (любое добавление/удаление). */
    private int allObjFrame = -1;
    private int allObjRev = -1;

    public final Vector getObjectsCached() {
        if(allObjFrame != physicsFrame || allObjRev != Room.objectsRevision) {
            getObjects();
            allObjFrame = physicsFrame;
            allObjRev = Room.objectsRevision;
        }
        return tVec2;
    }

    public void addObject(RoomObject obj) {
        invalidateNearObjects();
        recomputePart(obj);
        
        if(obj.getPart() == -1) recomputePart(obj, false);
        
        if(obj.getPart() == -1 && DeveloperMenu.debugMode) {
            System.out.println("House: Объект "+obj.toString()+" находится вне карты и не может быть добавлен");
            System.out.println(obj.getPosX() + " " + obj.getPosY() + " " + obj.getPosZ());
        }
    }

    public void removeObject(RoomObject obj) {
        if(obj.getPart() == -1) return;
        
        rooms[obj.getPart()].removeObject(obj);
        invalidateNearObjects();
    }

    public void recomputePart(RoomObject obj) {
        recomputePart(obj, true);
    }
    
    public void recomputePart(RoomObject obj, boolean rayCast) {
        if(!obj.isNeedRecomputePart()) return;

        int x = obj.getPosX();
        int z = obj.getPosZ();
        int y = obj.getPosY();
        int oldPart = obj.getPart();
        int newPart = calcPart(oldPart, x, y, z, rayCast);

        if(obj.getNewPart() != -1) {
            newPart = obj.getNewPart();
            obj.setNewPart(-1);
        }
        if(newPart == -1) newPart = oldPart;

        if(newPart != -1) {
            if(newPart != oldPart || oldPart == -1) {
                if(oldPart != -1) rooms[oldPart].removeObject(obj);
                
                rooms[newPart].addObject(obj);
                obj.setPart(newPart);
                invalidateNearObjects();
            }
        }

    }

    public int calcPart(int oldPart, int x, int y, int z) {
        return calcPart(oldPart, x, y, z, true);
    }
    
    public int calcPart(int oldPart, int x, int y, int z, boolean rayCast) {
        if(rooms.length == 1) return 0;

        if(oldPart != -1) {
            if(rayCast && rooms[oldPart].isPointOnMesh(x, y, z) != -1) return oldPart;
            if(!rayCast && rooms[oldPart].isPointInRoomBox(x, y, z)) return oldPart;

            Room[] neighbours2 = neighbours[oldPart];
            for(int i=0; i<neighbours2.length; i++) {
                Room room = neighbours2[i];
                if(room == null) continue;
                
                if(rayCast && room.isPointOnMesh(x, y, z) != -1) return room.getId();
                else if(!rayCast && room.isPointInRoomBox(x, y, z)) return room.getId();
            }
        }

        for(int i=0; i<rooms.length; i++) {
            Room room = rooms[i];
            
            if(rayCast && room.isPointOnMesh(x, y, z) != -1) return i;
            else if(!rayCast && room.isPointInRoomBox(x, y, z)) return i;
        }

        return -1;
    }

    /**
     * Кэш буферов под getParts(): раньше на каждый кадр создавалось два int[],
     * причём второй был точной копией первого. Метод вызывается один раз за
     * кадр из GameScreen.paint(), результат используется сразу и нигде не
     * сохраняется (House.render() только читает его), поэтому
     * переиспользование массива безопасно.
     */
    private static final int[][] partsCache = new int[16][];

    private static int[] partsBuffer(int len) {
        if(len < partsCache.length) {
            int[] buf = partsCache[len];
            if(buf == null) {
                buf = new int[len];
                partsCache[len] = buf;
            }
            return buf;
        }
        return new int[len];
    }

    public static int[] getParts(House house, int part, Matrix cam) {
        if(part == -1) {
            int[] out0 = partsBuffer(1);
            out0[0] = 0;
            return out0;
        }
        Room[] rooms = house.getNeighbourRooms(part);
        if(rooms == null || rooms.length==0) {
            int[] out1 = partsBuffer(1);
            out1[0] = part;
            return out1;
        }
        
        int[] out = partsBuffer(rooms.length+1);
        out[0] = part;
        int roomsCount = 1;

        int x = cam.m03;
        int y = cam.m13;
        int z = cam.m23;

        for(int i=0; i<rooms.length; i++) {
            Room room = rooms[i];
            
            if(!boxRoomTesting && room.isPointOnMesh(x, y, z) != -1) {
                out[roomsCount] = room.getId();
                roomsCount++;
            } else if(boxRoomTesting && room.isPointInRoomBox(x, y, z)) {
                out[roomsCount] = room.getId();
                roomsCount++;
            } 
        }

        int[] out2 = partsBuffer(roomsCount);
        System.arraycopy(out, 0, out2, 0, roomsCount);
        
        return out2;
    }

    public boolean isNear(int id1, int id2) {
        if(id1 == -1 || id2 == -1) return false;
        if(id1 == id2) return true;
        if(id1 >= neighbours.length) return false;

        Room[] rooms = neighbours[id1];
        if(rooms == null) return false;

        for(int i=0; i<rooms.length; i++) {
            Room room = rooms[i];
            if(room != null && room.getId() == id2) return true;
        }

        return false;
    }

    public void sortLights(Light[] lights) {
        for(int i=0; i<lights.length; i++) {
            if(lights[i].part == -1) lights[i].part = calcPart(-1, lights[i].pos.x, lights[i].pos.y, lights[i].pos.z);
        }

        Vector lits = new Vector();
        for(int i=0; i<rooms.length; i++) {
            Room room = rooms[i];
            if(room == null) continue;
            
            lits.removeAllElements();
            int id = room.getId();
            for(int x=0; x<lights.length; x++) {
                if(isNear(id, lights[x].part)) lits.addElement(lights[x]);
            }
            
            if(lits.size() > 0) {
                room.lights = new Light[lits.size()];
                for(int x=0; x<lits.size(); x++) {
                    room.lights[x] = (Light) lits.elementAt(x);
                }
            }
            
        }
    }

}
