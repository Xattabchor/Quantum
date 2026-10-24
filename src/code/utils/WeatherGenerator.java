package code.utils;

/**
 *
 * @author Romash
 */
import code.AI.Player;
import code.Math.FastMath;
import code.Rendering.DirectX7;
import java.util.Random;
import javax.microedition.lcdui.Graphics;

public class WeatherGenerator {
    
  public byte generate; 
/*
 * 0 - Nothing 
 * 1 - Rain
 * (2 - Snow: вырезан по запросу автора; SNOW-уровни остаются без частиц)
 */
  // ARM9: координаты частиц хранятся в int Q8 (1/256 пиксела) вместо float.
  // Деления на частицу заменены умножением на магические числа (INV_DEPTH,
  // FastMath.div50); константы кадра делятся один раз за вызов. Квантование
  // 1/256 пиксела на глаз неотличимо, сигнатуры методов не изменились.
  public int[] coords; // X,Y в Q8 (пиксел = значение >> 8)
  public int[] info; //Type,Depth
  public int screenWidth; //Screen width
  public int screenHeight; //Screen height
  public int rainColorNear; //ARGB color of near rain
  public int rainColorFar;//ARGB color of far rain
  public boolean lighting=false; //Lightings
  public boolean lightingTwice=false; //Gen new lighting in second
  public long lastLighting;
  public long nextLighting;
  public int[] lightingTimes;
  public int rainSize; //Rain size
  public int centerX; //Screen center x
  public int centerY; //Screen center y
  public int rainSpeed; //Rain speed
  public boolean isZoom=false;
  public boolean lastUnderRoof;
  // ARM9: new Random() на каждый вызов move*/update/createParticles (до 4
  // аллокаций + инициализация генератора на кадр при активной погоде)
  // заменён одним полем. Погода стохастична, последовательность не
  // сохраняется в сейвах.
  private final Random rand = new Random();
  
  public final int horizonLine = -5;
  //public int animationHorizonLine = 0;
  public final int horizonSize = 90;
  
  /** ceil(2^24/d) для d=1..65: деление на глубину одним умножением. */
  private static final int[] INV_DEPTH = new int[66];
  static {
      for(int d = 1; d < 66; d++) INV_DEPTH[d] = (int) (((1L << 24) + d - 1) / d);
  }

  public WeatherGenerator(int scrwidth,int scrheight,int quality,
          int rcn,int rcf,
          boolean light, int[] lightingTimes,
          int rs,
          int cenx,int ceny,
          int rspeed) {
      
      screenWidth=scrwidth;
      screenHeight=scrheight;
      coords=new int[quality*scrwidth/240*scrheight/320*2];
      info=new int[quality*scrwidth/240*scrheight/320*2];
      clearBuffers();
      lighting=light;
      lastLighting = System.currentTimeMillis();
      this.lightingTimes = lightingTimes;
      if(lightingTimes!=null) genNewLighting(null);
      rainColorNear=rcn;
      rainColorFar=rcf;
      rainSize=rs*scrheight/320;
      if(rainSize<rs) rainSize=rs;
      centerX=cenx;
      centerY=ceny;
      rainSpeed=rspeed*scrheight/320;
      generate=0;
  }
  
  public void clearBuffers() {
      
      for(int i=0;i<coords.length;i++) {coords[i]=0;}
      for(int i=0;i<info.length;i++) {info[i]=0;}
      
  }
  
  public void createParticles() {
      Random r = this.rand;
      for(int i=0;i<coords.length/2;i++) { 
          
          if(info[i*2]==0) { // Генерируем новые партиклы
              coords[i*2+1]=r.nextInt(screenHeight)<<8;
              coords[i*2]=r.nextInt(screenWidth)<<8;
              info[i*2]=generate;
              info[i*2+1]=r.nextInt(100);
              
          }
      }
  }
  
    public void moveX(int x,int frameTime) {
        int xx=FastMath.div50(x*3*frameTime*256);   // Q8
      for(int i=0;i<coords.length/2;i++) { 
          
              int depth=info[i*2+1]/22;
              if(depth<=0) depth=1;
              if(depth>65) depth=65;
              
          if(info[i*2]!=0) { 
              coords[i*2]+=(int)(((long)xx*INV_DEPTH[depth])>>24);
          }
      }
  }
    
        public void moveX2(int x,int frameTime) {
int xx=FastMath.div50(x*4*frameTime*256);   // Q8
      for(int i=0;i<coords.length/2;i++) { 
          if(info[i*2]!=0) { 
              coords[i*2]+=xx;
          }
      }
  }
    
    public void moveY(int y,int frameTime) {
int yy=(int)((y*6656L*frameTime)/250);   // y*1.3*4/50 в Q8 = y*6656/250
      for(int i=0;i<coords.length/2;i++) { 
          
              /*int depth=info[i*2+1]/25;
              if(depth<=0) depth=1;
              if(depth>65) depth=65;*/
              
          if(info[i*2]!=0) { 
              coords[i*2+1]+=yy;
          }
      }
  }
        public void moveY2(int y,int frameTime) {
int yy=(int)((y*624L*frameTime)/2500);   // y*1.3*3/80/50 в Q8 = y*624/2500
      for(int i=0;i<coords.length/2;i++) { 
          
              int depth=info[i*2+1]/25;
              if(depth<=0) depth=1;
              if(depth>65) depth=65;
              
          if(info[i*2]!=0) { 
              coords[i*2+1]+=(int)(((long)yy*INV_DEPTH[depth])>>24);
          }
      }
  }
    
    public void move(int z,int frameTime,int diry) {
      int zz4=(int)((z*11L*frameTime)/700);   // zz/3.5 = z*2.75*ft/50/3.5 = z*ft*11/700
      isZoom=true;
      int mz=65536+(int)((z*frameTime*8192L)/625);   // 1+0.05*z/5*ft/50 = 1+z*ft/5000, Q16
      Random r = this.rand;
      int centerY2=centerY*(90+diry*7/5)/90;
      int cx8=centerX<<8, cy8=centerY2<<8;
      for(int i=0;i<coords.length/2;i++) { 
          
              
          if(info[i*2]!=0) { 
              int x=coords[i*2]=cx8+(int)(((long)(coords[i*2]-cx8)*mz)>>16);
              int y=coords[i*2+1]=cy8+(int)(((long)(coords[i*2+1]-cy8)*mz)>>16);
              
              info[i*2+1]-=zz4;
              
          if(x>(screenWidth<<8) || x<0 || y>(screenHeight<<8) || y<0) {
              coords[i*2]=r.nextInt(screenWidth)<<8;
              coords[i*2+1]=r.nextInt(screenHeight)<<8;
              info[i*2+1]=100;
          }
          }
          else if(info[i*2]==0) {             
              coords[i*2]=r.nextInt(screenWidth)<<8;
              coords[i*2+1]=r.nextInt(screenHeight)<<8;
              info[i*2+1]=100;
              info[i*2]=generate;
          }
      }
  }
    

    
    
    public void moveB(int z,int frameTime,int diry) {
      int zz4=(int)((z*11L*frameTime)/800);   // zz/4 = z*2.75*ft/50/4 = z*ft*11/800
      int mz=65536-(int)((z*frameTime*8192L)/625);   // 1-z*ft/5000, Q16
      Random r = this.rand;
      int centerY2=centerY*(90+diry*7/5)/90;
      int cx8=centerX<<8, cy8=centerY2<<8;
      for(int i=0;i<coords.length/2;i++) { 
          
              
          if(info[i*2]!=0) { 
              int x=coords[i*2]=cx8+(int)(((long)(coords[i*2]-cx8)*mz)>>16);
              int y=coords[i*2+1]=cy8+(int)(((long)(coords[i*2+1]-cy8)*mz)>>16);
              
              info[i*2+1]+=zz4;
              
          if(info[i*2+1]>100) {
              if(r.nextInt(2)==0) {
              coords[i*2]=(r.nextInt(2)*screenWidth)<<8;
              coords[i*2+1]=r.nextInt(screenHeight)<<8; 
          }
              else{
              coords[i*2]=r.nextInt(screenWidth)<<8;
              coords[i*2+1]=(r.nextInt(2)*screenHeight)<<8; 
          }
              info[i*2+1]=0;
          }
          }
      }
  }
  
  public void update(int frameTime,int diry, boolean underRoof) {
      
      Random r = this.rand;
      
      
      for(int i=0;i<coords.length/2;i++) { // Удаляем партиклы за экраном
          
          
          if(coords[i*2+1]<-512) coords[i*2+1]+=screenHeight*256-256;
          if(coords[i*2]>screenWidth*256) coords[i*2]=coords[i*2]-screenWidth*256;
          if(coords[i*2]<-512) coords[i*2]=coords[i*2]+screenWidth*256+512;
          
          if(isZoom==false) {
          if(coords[i*2+1]>screenHeight*256) info[i*2]=0;
          if(coords[i*2+1]>((screenHeight*(diry+90)/180)+screenHeight/5)*256 && info[i*2+1]>40)  { info[i*2]=0; }
          
          if(coords[i*2]>(screenWidth+10)*256) info[i*2]=0;
          if(coords[i*2]<-12*256) info[i*2]=0;
         
          
          
          if(info[i*2]==0 && !underRoof) { // Генерируем новые партиклы
              if(!lastUnderRoof) {
                  coords[i*2+1] = 0;
                  coords[i*2] = r.nextInt(screenWidth)<<8;
                  info[i*2] = generate;
                  info[i*2+1] = r.nextInt(100);
              } else {
                  coords[i*2+1] = r.nextInt(screenHeight)<<8;
                  coords[i*2] = r.nextInt(screenWidth)<<8;
                  info[i*2] = generate;
                  info[i*2+1] = 100;
              }
          }
          
          
          }
          
          if(info[i*2]==1) { // Двигаем дощ (снег вырезан)
          
              int depth=info[i*2+1];
              if(depth<=0) depth=1;
              if(depth>100) depth=100;
              depth=100-depth;
              if(info[i*2+1]>100) info[i*2+1]=100;
              if(info[i*2+1]<0) info[i*2+1]=0;
              int speed8=(rainSpeed*(depth/40+2))<<7;   // rainSpeed*(depth/40+2)/2 в Q8
              int add8=FastMath.div50(speed8*frameTime);
              coords[i*2]-=add8>>2;
              coords[i*2+1]+=add8;
          }
          
          
      }
      
      if( lighting && System.currentTimeMillis()>=nextLighting ) {
          lastLighting = System.currentTimeMillis();
          genNewLighting(r);
      }
      
      isZoom=false;
      lastUnderRoof = underRoof;
      
  }
  
  public void genNewLighting(Random r) {
      if(r==null) r = this.rand;
      
      nextLighting = lastLighting + r.nextInt(lightingTimes[1]-lightingTimes[0])+lightingTimes[0];
      
      boolean oldLightingTwice = lightingTwice;
      lightingTwice = (r.nextInt(lightingTwice?5:2)==0);
      if(lightingTwice) nextLighting = lastLighting + 200+r.nextInt(175)-(oldLightingTwice?75:0);
  }
  
  public void paint(Graphics g,int xx) {
      
      /*int light = 0;
      final int litTim = 1000;
      
      if(lighting && System.currentTimeMillis()-lastLighting<litTim) {
          light = 100-(int)(System.currentTimeMillis()-lastLighting)*100/litTim;
      }*/
      
      boolean light = lighting && System.currentTimeMillis()-lastLighting<135;
      
      for(int i=0;i<coords.length/2;i++) { // Рендерим партиклы (снег вырезан)
          if(info[i*2]==1) {
              
              int depth=info[i*2+1];
              
              int size=(rainSize*(100-depth)+depth)/100;
              if(depth<0) size=rainSize*(100-depth)/100;
              if(depth<0) depth=0;
              if(depth>100) depth=100;
              int col = mixColor(rainColorNear,rainColorFar,depth);
              //if(light>0) col = mixColor(col,mixColor(0xffffff,0xcccccc,depth),light);
              if(light) col = 0xffffff;
              g.setColor(col);
              int x=coords[i*2]>>8;
              int y=coords[i*2+1]>>8;
              
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
        cr=FastMath.div100(cr);
        cg=FastMath.div100(cg);
        cb=FastMath.div100(cb);
        return ((cr<<16) | (cg<<8) | cb);

    }


    
    public final void lightingEffect(DirectX7 g3d, float rotateX/*, int frameTime, boolean underRoof*/) {
        
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
