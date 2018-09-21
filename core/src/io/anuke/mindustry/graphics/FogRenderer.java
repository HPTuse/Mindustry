package io.anuke.mindustry.graphics;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap.Format;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Disposable;
import io.anuke.mindustry.entities.Unit;
import io.anuke.mindustry.game.EventType.TileChangeEvent;
import io.anuke.mindustry.game.EventType.WorldLoadGraphicsEvent;
import io.anuke.mindustry.world.Tile;
import io.anuke.ucore.core.Core;
import io.anuke.ucore.core.Events;
import io.anuke.ucore.core.Graphics;
import io.anuke.ucore.entities.EntityDraw;
import io.anuke.ucore.graphics.Draw;
import io.anuke.ucore.graphics.Fill;
import io.anuke.ucore.scene.utils.ScissorStack;

import java.nio.ByteBuffer;

import static io.anuke.mindustry.Vars.*;

/**Used for rendering fog of war. A framebuffer is used for this.*/
public class FogRenderer implements Disposable{
    private static final int extraPadding = 3;
    private static final int fshadowPadding = 1;

    private TextureRegion region = new TextureRegion();
    private FrameBuffer buffer;
    private ByteBuffer pixelBuffer;
    private Array<Tile> changeQueue = new Array<>();
    private int padding;
    private int shadowPadding;
    private Rectangle rect = new Rectangle();
    private boolean dirty;

    private boolean isOffseted;
    private int offsettedX, offsettedY;

    public FogRenderer(){
        Events.on(WorldLoadGraphicsEvent.class, event -> {
            if(!isOffseted){
                dispose();
            }

            padding = world.getSector() != null ? mapPadding + extraPadding : 0;
            shadowPadding = world.getSector() != null ? fshadowPadding : -1;

            FrameBuffer lastBuffer = buffer;

            buffer = new FrameBuffer(Format.RGBA8888, world.width() + padding*2, world.height() + padding*2, false);
            changeQueue.clear();

            //clear buffer to black
            buffer.begin();
            Graphics.clear(0, 0, 0, 1f);

            if(isOffseted){
                Core.batch.getProjectionMatrix().setToOrtho2D(-padding, -padding, buffer.getWidth(), buffer.getHeight());
                Core.batch.begin();
                Core.batch.draw(lastBuffer.getColorBufferTexture(), offsettedX, offsettedY + lastBuffer.getColorBufferTexture().getHeight(),
                            lastBuffer.getColorBufferTexture().getWidth(), -lastBuffer.getColorBufferTexture().getHeight());
                Core.batch.end();
            }
            buffer.end();

            if(isOffseted){
                lastBuffer.dispose();
            }

            for(int x = 0; x < world.width(); x++){
                for(int y = 0; y < world.height(); y++){
                    Tile tile = world.tile(x, y);
                    if(tile.getTeam() == players[0].getTeam() && tile.block().synthetic() && tile.block().viewRange > 0){
                        changeQueue.add(tile);
                    }
                }
            }

            pixelBuffer = ByteBuffer.allocateDirect(world.width() * world.height() * 4);
            dirty = true;

            isOffseted = false;
        });

        Events.on(TileChangeEvent.class, event -> threads.runGraphics(() -> {
            if(event.tile.getTeam() == players[0].getTeam() && event.tile.block().synthetic() && event.tile.block().viewRange > 0){
                changeQueue.add(event.tile);
            }
        }));
    }

    public void setLoadingOffset(int x, int y){
        isOffseted = true;
        offsettedX = x;
        offsettedY = y;
    }

    public void writeFog(){
        if(buffer == null) return;

        buffer.begin();
        pixelBuffer.position(0);
        Gdx.gl.glPixelStorei(GL20.GL_PACK_ALIGNMENT, 1);
        Gdx.gl.glReadPixels(padding, padding, world.width(), world.height(), GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE, pixelBuffer);

        pixelBuffer.position(0);
        for(int i = 0; i < world.width() * world.height(); i++){
            byte r = pixelBuffer.get();
            if(r != 0){
                world.tile(i).setVisibility((byte)1);
            }
            pixelBuffer.position(pixelBuffer.position() + 3);
        }
        buffer.end();
    }

    public int getPadding(){
        return padding;
    }

    public void draw(){
        if(buffer == null) return;

        float vw = Core.camera.viewportWidth * Core.camera.zoom;
        float vh = Core.camera.viewportHeight * Core.camera.zoom;

        float px = Core.camera.position.x - vw / 2f;
        float py = Core.camera.position.y - vh / 2f;

        float u = (px / tilesize + padding) / buffer.getWidth();
        float v = (py / tilesize + padding) / buffer.getHeight();

        float u2 = ((px + vw) / tilesize + padding) / buffer.getWidth();
        float v2 = ((py + vh) / tilesize + padding) / buffer.getHeight();

        Core.batch.getProjectionMatrix().setToOrtho2D(-padding * tilesize, -padding * tilesize, buffer.getWidth() * tilesize, buffer.getHeight() * tilesize);

        Draw.color(Color.WHITE);

        buffer.begin();

        boolean pop = ScissorStack.pushScissors(rect.set((padding-shadowPadding), (padding-shadowPadding),
                    (world.width() + shadowPadding*2) ,
                    (world.height() + shadowPadding*2)));

        Graphics.begin();
        EntityDraw.setClip(false);

        renderer.drawAndInterpolate(playerGroup, player -> !player.isDead() && player.getTeam() == players[0].getTeam(), Unit::drawView);
        renderer.drawAndInterpolate(unitGroups[players[0].getTeam().ordinal()], unit -> !unit.isDead(), Unit::drawView);

        for(Tile tile : changeQueue){
            float viewRange = tile.block().viewRange;
            if(viewRange < 0) continue;
            Fill.circle(tile.drawx(), tile.drawy(), tile.block().viewRange);
        }

        changeQueue.clear();

        if(dirty){
            for(int i = 0; i < world.width() * world.height(); i++){
                Tile tile = world.tile(i);
                if(tile.discovered()){
                    Fill.rect(tile.worldx(), tile.worldy(), tilesize, tilesize);
                }
            }
            dirty = false;
        }

        EntityDraw.setClip(true);
        Graphics.end();
        buffer.end();

        if(pop) ScissorStack.popScissors();

        region.setTexture(buffer.getColorBufferTexture());
        region.setRegion(u, v2, u2, v);

        Core.batch.setProjectionMatrix(Core.camera.combined);
        Graphics.shader(Shaders.fog);
        renderer.pixelSurface.getBuffer().begin();
        Graphics.begin();

        Core.batch.draw(region, px, py, vw, vh);

        Graphics.end();
        renderer.pixelSurface.getBuffer().end();
        Graphics.shader();

        Graphics.setScreen();
        Core.batch.draw(renderer.pixelSurface.texture(), 0, Gdx.graphics.getHeight(), Gdx.graphics.getWidth(), -Gdx.graphics.getHeight());
        Graphics.end();
    }

    public Texture getTexture(){
        return buffer.getColorBufferTexture();
    }

    @Override
    public void dispose(){
        if(buffer != null) buffer.dispose();
    }
}
