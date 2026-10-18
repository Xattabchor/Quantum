package code.AI;

import code.Gameplay.Objects.GameObject;
import code.Math.Vector3D;
import java.util.Vector;

/** Frozen target selection/FOV from 8c33787, independent of new helpers. */
public final class AIReference {
    public static boolean fov(GameObject observer,GameObject observable) {
        Vector3D lookDir=new Vector3D(observer.character.transform.m02,0,observer.character.transform.m22);
        lookDir.setLength(4096);
        Vector3D observableDir=new Vector3D(observable.character.transform.m03-observer.character.transform.m03,0,
                observable.character.transform.m23-observer.character.transform.m23);
        observableDir.setLength(4096);
        boolean result=lookDir.dot(observableDir)>0;
        result|=observer.character.distance(observable.character)<2500*2500;
        return result;
    }
    public static GameObject findBot(Bot observer,Vector objs,GameObject ignore,int[] fractions) {
        AIStats.scans++;
        long dis=Long.MAX_VALUE;GameObject bot=null;
        if(objs.isEmpty())return null;
        for(int i=0;i<objs.size();i++) {
            if(objs.elementAt(i) instanceof GameObject) {
                GameObject obj=(GameObject)objs.elementAt(i);
                if(!obj.isDead() && (obj instanceof Bot || obj instanceof Player) && obj!=ignore) {
                    long dis2=observer.character.distance(obj.character);
                    if(dis2>=dis)continue;
                    if(observer.visiblityCheck && !fov(observer,obj))continue;
                    if(obj instanceof Bot) {
                        if(Bot.contains(fractions,((Bot)obj).fraction)){dis=dis2;bot=obj;}
                    } else if(Bot.contains(fractions,0)){dis=dis2;bot=obj;}
                }
            }
        }
        return bot;
    }
}
