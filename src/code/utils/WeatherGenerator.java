package code.utils;

/**
 *
 * @author Romash
 */
import code.AI.Player;
import code.Rendering.DirectX7;
import code.Rendering.ScreenLines;
import java.util.Random;
import javax.microedition.lcdui.Graphics;

public class WeatherGenerator {
    
  private ScreenLines nativeParticles;
  private static final int RAIN_LIMIT=16;
  private int rendererMode=Main.renderer;
  private int[] activeParticles;
  private int activeCount,rainSlots;
  private float[] indexedCoords;
  private int[] indexedInfo;
  private Random rainRandom;

  public void setRenderer(int renderer) {
      if(rendererMode!=renderer) {
          rendererMode=renderer;activeParticles=null;
      }
  }

  // Build once on entering M3G rain or replacing the public particle arrays.
  // Keep all live snow, but only 16 rain/emitter slots. Other rain is dormant.
  private int particleCount() {
      if(rendererMode!=DirectX7.M3G || generate==2) {
          activeParticles=null;return coords.length/2;
      }
      if(activeParticles!=null && indexedCoords==coords && indexedInfo==info) return activeCount;
      int total=coords.length/2,snow=0;
      for(int i=0;i<total;i++) if(info[i*2]==2) snow++;
      rainSlots=Math.min(RAIN_LIMIT,total-snow);
      activeParticles=new int[rainSlots+snow];
      int rain=0,tail=rainSlots;
      for(int i=0;i<total;i++) {
          if(info[i*2]==2) activeParticles[tail++]=i;
          else if(rain<rainSlots) activeParticles[rain++]=i;
      }
      activeCount=tail;indexedCoords=coords;indexedInfo=info;
      if(rainRandom==null) rainRandom=new Random();
      return activeCount;
  }

  private int particleIndex(int slot) {return activeParticles==null?slot:activeParticles[slot];}
  private boolean canEmit(int slot) {return activeParticles==null || slot<rainSlots;}
  private Random particleRandom() {return activeParticles==null?new Random():rainRandom;}

  private void retireSnow() {
      if(activeParticles==null) return;
      for(int slot=rainSlots;slot<activeCount;) {
          int index=activeParticles[slot];
          if(info[index*2]==2) {slot++;continue;}
          if(rainSlots<RAIN_LIMIT) {
              activeParticles[slot]=activeParticles[rainSlots];
              activeParticles[rainSlots++]=index;
              if(slot<rainSlots) slot=rainSlots;
          } else activeParticles[slot]=activeParticles[--activeCount];
      }
  }
  public byte generate; 
/*
 * 0 - Nothing 
 * 1 - Rain
 * 2 - Snow
 */
  public float[] coords; // X,Y
  public int[] info; //Type,Depth
  public int screenWidth; //Screen width
  public int screenHeight; //Screen height
  public int rainColorNear; //ARGB color of near rain
  public int rainColorFar;//ARGB color of far rain
  public int snowColorNear; //ARGB color of near snow
  public int snowColorFar;//ARGB color of far snow
  public boolean lighting=false; //Lightings
  public boolean lightingTwice=false; //Gen new lighting in second
  public long lastLighting;
  public long nextLighting;
  public int[] lightingTimes;
  public int snowSize; //Snow size
  public int rainSize; //Rain size
  public int centerX; //Screen center x
  public int centerY; //Screen center y
  public int snowSpeed; //Snow speed
  public int rainSpeed; //Rain speed
  public boolean isZoom=false;
  public boolean lastUnderRoof;
  
  public final int horizonLine = -5;
  //public int animationHorizonLine = 0;
  public final int horizonSize = 90;
  
  public WeatherGenerator(int scrwidth,int scrheight,int quality,
          int rcn,int rcf,int scn,int scf,
          boolean light, int[] lightingTimes,
          int ss,int rs,
          int cenx,int ceny,
          int sspeed,int rspeed) {
      
      screenWidth=scrwidth;
      screenHeight=scrheight;
      coords=new float[quality*scrwidth/240*scrheight/320*2];
      info=new int[quality*scrwidth/240*scrheight/320*2];
      clearBuffers();
      lighting=light;
      lastLighting = System.currentTimeMillis();
      this.lightingTimes = lightingTimes;
      if(lightingTimes!=null) genNewLighting(null);
      rainColorNear=rcn;
      rainColorFar=rcf;
      snowColorNear=scn;
      snowColorFar=scf;
      snowSize=ss*scrheight/320;
      if(snowSize<ss) snowSize=ss;
      rainSize=rs*scrheight/320;
      if(rainSize<rs) rainSize=rs;
      centerX=cenx;
      centerY=ceny;
      snowSpeed=sspeed*scrheight/320;
      rainSpeed=rspeed*scrheight/320;
      generate=0;
  }
  
  public void clearBuffers() {
      activeParticles=null;
      
      for(int i=0;i<coords.length;i++) {coords[i]=0F;}
      for(int i=0;i<info.length;i++) {info[i]=0;}
      
  }
  
  public void createParticles() {
      int count=particleCount();
      Random r = particleRandom();
      for(int slot=0;slot<count;slot++) {
          int i=particleIndex(slot);
          
          if(info[i*2]==0 && canEmit(slot)) { // Генерируем новые партиклы
              coords[i*2+1]=r.nextInt(screenHeight);
              coords[i*2]=r.nextInt(screenWidth);
              info[i*2]=generate;
              info[i*2+1]=r.nextInt(100);
              
          }
      }
  }
  
    public void moveX(int x,int frameTime) {
        float xx=x*3*frameTime/50;
      for(int slot=0,count=particleCount();slot<count;slot++) {
          int i=particleIndex(slot);
          
              int depth=info[i*2+1]/22;
              if(depth<=0) depth=1;
              if(depth>65) depth=65;
              
          if(info[i*2]!=0) { 
              coords[i*2]+=xx/depth;
          }
      }
  }
    
        public void moveX2(int x,int frameTime) {
float xx=x*4*frameTime/50;
      for(int slot=0,count=particleCount();slot<count;slot++) {
          int i=particleIndex(slot);
          if(info[i*2]!=0) { 
              coords[i*2]+=xx;
          }
      }
  }
    
    public void moveY(int y,int frameTime) {
float yy=y*1.3f*4*frameTime/50;
      for(int slot=0,count=particleCount();slot<count;slot++) {
          int i=particleIndex(slot);
          
              /*int depth=info[i*2+1]/25;
              if(depth<=0) depth=1;
              if(depth>65) depth=65;*/
              
          if(info[i*2]!=0) { 
              coords[i*2+1]+=yy;
          }
      }
  }
        public void moveY2(int y,int frameTime) {
float yy=y*1.3f*3*frameTime/80/50;
      for(int slot=0,count=particleCount();slot<count;slot++) {
          int i=particleIndex(slot);
          
              int depth=info[i*2+1]/25;
              if(depth<=0) depth=1;
              if(depth>65) depth=65;
              
          if(info[i*2]!=0) { 
              coords[i*2+1]+=yy/depth;
          }
      }
  }
    
    public void move(int z,int frameTime,int diry) {
      float zz=z*2.75f*frameTime/50;
      isZoom=true;
      float mz=1.0F+0.05F*z/5*frameTime/50;
      int count=particleCount();
      Random r = particleRandom();
      int centerY2=centerY*(90+diry*7/5)/90;
      for(int slot=0;slot<count;slot++) {
          int i=particleIndex(slot);
          
              
          if(info[i*2]!=0) { 
              float x=coords[i*2]=(coords[i*2]-centerX)*mz+centerX;
              float y=coords[i*2+1]=(coords[i*2+1]-centerY2)*mz+centerY2;
              
              info[i*2+1]-=zz/3.5f;
              
          if(x>screenWidth || x<0 || y>screenHeight || y<0) {
              coords[i*2]=r.nextInt(screenWidth);
              coords[i*2+1]=r.nextInt(screenHeight);
              info[i*2+1]=100;
          }
          }
          else if(info[i*2]==0 && canEmit(slot)) {
              coords[i*2]=r.nextInt(screenWidth);
              coords[i*2+1]=r.nextInt(screenHeight);
              info[i*2+1]=100;
              info[i*2]=generate;
          }
      }
  }
    

    
    
    public void moveB(int z,int frameTime,int diry) {
      float zz=z*2.75f*frameTime/50;
      float mz=1.0F-0.05F*z/5*frameTime/50;
      int count=particleCount();
      Random r = particleRandom();
      int centerY2=centerY*(90+diry*7/5)/90;
      for(int slot=0;slot<count;slot++) {
          int i=particleIndex(slot);
          
              
          if(info[i*2]!=0) { 
              float x=coords[i*2]=(coords[i*2]-centerX)*mz+centerX;
              float y=coords[i*2+1]=(coords[i*2+1]-centerY2)*mz+centerY2;
              
              info[i*2+1]+=zz/4;
              
          if(info[i*2+1]>100) {
              if(r.nextInt(2)==0) {
              coords[i*2]=r.nextInt(2)*screenWidth;
              coords[i*2+1]=r.nextInt(screenHeight); 
          }
              else{
              coords[i*2]=r.nextInt(screenWidth);
              coords[i*2+1]=r.nextInt(2)*screenHeight; 
          }
              info[i*2+1]=0;
          }
          }
      }
  }
  
  public void update(int frameTime,int diry, boolean underRoof) {
      
      int count=particleCount();
      Random r = particleRandom();
      
      
      for(int slot=0;slot<count;slot++) {
          int i=particleIndex(slot); // Удаляем партиклы за экраном
          
          
          if(coords[i*2+1]<-2) coords[i*2+1]+=screenHeight-1;
          if(coords[i*2]>screenWidth) coords[i*2]=coords[i*2]-screenWidth;
          if(coords[i*2]<-2) coords[i*2]=coords[i*2]+screenWidth+2;
          
          if(isZoom==false) {
          if(coords[i*2+1]>screenHeight) info[i*2]=0;
          if(coords[i*2+1]>(screenHeight*(diry+90)/180)+screenHeight/5 && info[i*2+1]>40)  { info[i*2]=0; }
          
          if(coords[i*2]>screenWidth+10) info[i*2]=0;
          if(coords[i*2]<-12) info[i*2]=0;
         
          
          
          if(info[i*2]==0 && !underRoof && canEmit(slot)) { // Генерируем новые партиклы
              if(!lastUnderRoof) {
                  coords[i*2+1] = 0;
                  coords[i*2] = r.nextInt(screenWidth);
                  info[i*2] = generate;
                  info[i*2+1] = r.nextInt(100);
              } else {
                  coords[i*2+1] = r.nextInt(screenHeight);
                  coords[i*2] = r.nextInt(screenWidth);
                  info[i*2] = generate;
                  info[i*2+1] = 100;
              }
          }
          
          
          }
          
          if(info[i*2]==2) { // Двигаем снег
              int rand=r.nextInt(3)-1;
              
              info[i*2+1]+=rand*snowSpeed*frameTime/50/4;
              int depth=info[i*2+1];
              if(depth<=0) depth=1;
              if(depth>100) depth=100;
              depth=100-depth;
              if(info[i*2+1]>100) info[i*2+1]=100;
              if(info[i*2+1]<0) info[i*2+1]=0;
              
              coords[i*2]+=rand*2*snowSpeed/((100-depth)/50+1)*frameTime/50/4;
              coords[i*2+1]+=(snowSpeed+r.nextInt(4)/2)*(depth/40+2)/2*frameTime/50;
          } else if(info[i*2]==1) { // Двигаем дощ
          
              int depth=info[i*2+1];
              if(depth<=0) depth=1;
              if(depth>100) depth=100;
              depth=100-depth;
              if(info[i*2+1]>100) info[i*2+1]=100;
              if(info[i*2+1]<0) info[i*2+1]=0;
              float speed=(rainSpeed)*(depth/40+2)/2*frameTime/50;
              coords[i*2]-=speed/4;
              coords[i*2+1]+=speed;
          }
          
          
      }
      
      retireSnow();
      if( lighting && System.currentTimeMillis()>=nextLighting ) {
          lastLighting = System.currentTimeMillis();
          genNewLighting(r);
      }
      
      isZoom=false;
      lastUnderRoof = underRoof;
      
  }
  
  public void genNewLighting(Random r) {
      if(r==null) r = new Random();
      
      nextLighting = lastLighting + r.nextInt(lightingTimes[1]-lightingTimes[0])+lightingTimes[0];
      
      boolean oldLightingTwice = lightingTwice;
      lightingTwice = (r.nextInt(lightingTwice?5:2)==0);
      if(lightingTwice) nextLighting = lastLighting + 200+r.nextInt(175)-(oldLightingTwice?75:0);
  }
  
  /** Compatibility wrapper for standalone callers. GameScreen uses prepareM3G
   * and submits weather inside the world's final transaction. */
  public boolean paintM3G(DirectX7 g) {
      ScreenLines particles=prepareM3G(g);
      return particles!=null && g.renderScreenLines(particles);
  }

  /** Snapshot geometry only: no bind, readback, drawing or simulation update. */
  public ScreenLines prepareM3G(DirectX7 g) {
      setRenderer(g.getRenderer());
      if(!g.canRenderM3G() || code.HUD.DeveloperMenu.drawPortals) {
          if(g.isM3GRequired()) throw new IllegalStateException("native weather unavailable");
          return null;
      }
      try {
          if(nativeParticles==null) nativeParticles=new ScreenLines();
          nativeParticles.clear();
          boolean light = lighting && System.currentTimeMillis()-lastLighting<135;
          int rain=0;
          for(int slot=0,count=particleCount();slot<count;slot++) {
              int i=particleIndex(slot);
              int type=info[i*2];
              if(type==1) {
                  if(rain++>=RAIN_LIMIT) continue;
                  int depth=info[i*2+1];
                  int size=(rainSize*(100-depth)+depth)/100;
                  if(depth<0) size=rainSize*(100-depth)/100;
                  depth=Math.max(0,Math.min(100,depth));
                  int color=light?0xffffff:mixColor(rainColorNear,rainColorFar,depth);
                  int x=(int)coords[i*2],y=(int)coords[i*2+1];
                  nativeParticles.line(x,y,x-size/4,y+size,1,color,false);
                  continue;
              }
              if(type != 2) continue;
              int depth=info[i*2+1];
              int base=snowSize;
              int size=(base*(100-depth)+depth)/100;
              if(depth<0) size=base*(100-depth)/100;
              depth=Math.max(0,Math.min(100,depth));
              int color=light?0xffffff:mixColor(snowColorNear,snowColorFar,depth);
              int x=(int)coords[i*2],y=(int)coords[i*2+1];
              if(size>1) nativeParticles.rect(x-size/2,y-size/2,size-1,size-1,color);
              else nativeParticles.rect(x,y,1,1,color);
          }
          return nativeParticles;
      } catch(OutOfMemoryError ex) {
          if(g.isM3GRequired()) throw ex;
          return null; // Legacy callers retain LCDUI fallback.
      }
  }

  /** Successful native weather is never duplicated after flush. */
  public void paintAfterScene(Graphics g,int xx,int renderer,boolean nativeWeather) {
      setRenderer(renderer);
      if(nativeWeather) {g.setColor(0xffffff);return;}
      if(renderer != DirectX7.M3G) {paint(g,xx);return;}
      paint(g,xx,false); // Original snow fallback, bounded cheap rain fallback.
      paintCheapRain(g,xx);
  }

  private void paintCheapRain(Graphics g,int xx) {
      particleCount();
      int count=activeParticles==null?coords.length/2:rainSlots;
      int step=activeParticles==null?Math.max(1,(count+RAIN_LIMIT-1)/RAIN_LIMIT):1;
      boolean light=lighting && System.currentTimeMillis()-lastLighting<135;
      g.setColor(light?0xffffff:mixColor(rainColorNear,rainColorFar,50));
      for(int slot=0;slot<count;slot+=step) {
          int i=particleIndex(slot);
          if(info[i*2]!=1) continue;
          int x=(int)coords[i*2],y=(int)coords[i*2+1];
          if(x<0 || x>=screenWidth || y>=screenHeight || y+3<0) continue;
          // Fixed one-pixel-wide vertical stroke: no size/depth math or geometry.
          g.drawLine(x,y+xx,x,y+xx+3);
      }
      g.setColor(0xffffff);
  }

  public void paint(Graphics g,int xx) { paint(g,xx,true); }

  private void paint(Graphics g,int xx,boolean includeRain) {
      
      /*int light = 0;
      final int litTim = 1000;
      
      if(lighting && System.currentTimeMillis()-lastLighting<litTim) {
          light = 100-(int)(System.currentTimeMillis()-lastLighting)*100/litTim;
      }*/
      
      boolean light = lighting && System.currentTimeMillis()-lastLighting<135;
      
      for(int slot=0,count=particleCount();slot<count;slot++) {
          int i=particleIndex(slot); // Рендерим партиклы
          if(info[i*2]==2) {
              
              int depth=info[i*2+1];
              int size=(snowSize*(100-depth)+depth)/100;
              if(depth<0) size=snowSize*(100-depth)/100;
              if(depth<0) depth=0;
              if(depth>100) depth=100;
              int col = mixColor(snowColorNear,snowColorFar,depth);
              if(light) col = 0xffffff;
              g.setColor(col);
              int x=(int)coords[i*2];
              int y=(int)coords[i*2+1];
              
              if(size>1)g.fillRect(x-size/2, y-size/2+xx, size-1, size-1);
              if(size<=1 )g.drawLine(x, y+xx, x,y+xx);
              
          } else if(includeRain && info[i*2]==1) {
              
              int depth=info[i*2+1];
              
              int size=(rainSize*(100-depth)+depth)/100;
              if(depth<0) size=rainSize*(100-depth)/100;
              if(depth<0) depth=0;
              if(depth>100) depth=100;
              int col = mixColor(rainColorNear,rainColorFar,depth);
              //if(light>0) col = mixColor(col,mixColor(0xffffff,0xcccccc,depth),light);
              if(light) col = 0xffffff;
              g.setColor(col);
              int x=(int)coords[i*2];
              int y=(int)coords[i*2+1];
              
              g.drawLine(x, y+xx, x-size/4,y+xx+size);
              
          }
      }
      

      g.setColor(0xffffff);
      
      
      //Looks like shit
      /* 
      if(lighting && System.currentTimeMillis()-lastLighting<=250) {
          int percentage = 1000-(int)(System.currentTimeMillis()-lastLighting)*1000/250;
          percentage = Matrix.sin(percentage*90/1000)*1000/Matrix.FP;
          
          int steps = 8;
          for(int i=0;i<steps;i++) {
              int size = screenHeight*percentage/1000/steps;
              g.fillRect(0,screenHeight*i/steps+screenHeight/2/steps-size/2,
                      screenWidth,size);
          }
          
          
      }*/
  }

    public int mixColor(int col1, int col2, int mix) {

        int cr = ((col1>>16)&0xff) * (100-mix) + ((col2>>16)&0xff) * mix;
        int cg = ((col1>>8)&0xff) * (100-mix) + ((col2>>8)&0xff) * mix;
        int cb = (col1&0xff) * (100-mix) + (col2&0xff) * mix;
        cr/=100;
        cg/=100;
        cb/=100;
        return ((cr<<16) | (cg<<8) | cb);

    }


    
    public final void lightingEffect(DirectX7 g3d, float rotateX/*, int frameTime, boolean underRoof*/) {
        // M3G storms change only the sky, even after native failure.
        if(g3d.getRenderer() == DirectX7.M3G) return;
        
        /*animationHorizonLine += frameTime*(underRoof?1:-1);
        if(animationHorizonLine>1000) return;
        if(animationHorizonLine<0) animationHorizonLine = 0;*/
        
        int horizonY = (int) (screenHeight/2 - horizonLine*screenHeight/g3d.fovY + rotateX*screenHeight/g3d.fovY );
        horizonY = (horizonY-g3d.centreY) *g3d.distY/(11 + g3d.distY) + g3d.centreY;
        
        /*if(animationHorizonLine>0) {
            horizonY = (
                    (screenHeight+1)*animationHorizonLine + horizonY*(1000-animationHorizonLine)
                    )/1000;
        }*/
            
        int ySize = horizonSize*g3d.height/g3d.fovY;
        
        if(horizonY>=screenHeight) return;

        int y = horizonY;
        if(y<0) y = 0;
        
        int[] scr = g3d.getDisplay();
        
        int x1, x2, col, scrcol;
        for(;y<screenHeight;y++) {
            
            x1 = y*screenWidth;
            x2 = x1 + screenWidth;
            col = ((y-horizonY)*255/ySize);//&0xfe;
            if(col>100) col = 100; //254 is max but its too bright so i used 100 (100&0xfe=100)
            col = (col&0xfe)*0x010101;
            
            while(x1<x2) {
                scr[x1]=(scrcol=(scr[x1]&0xFEFEFE)+col)|((( scrcol>>>8 ) &0x010101)*0xFF);
                x1++;
            }
            
        }
        
    }
  
  
}
