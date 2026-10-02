package panel.motion.icon;

import com.lottie4j.core.model.animation.Animation;
import com.lottie4j.fxplayer.LottiePlayer;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import panel.motion.MotionService;

/** Renderer Lottie nativo (Lottie4J/Canvas). Sem WebView. */
public class LottieAnimatedIcon implements AnimatedIcon {
    private final LottiePlayer player;
    private final StackPane box;
    private final MotionService motion;
    private final int pixels;

    public LottieAnimatedIcon(Animation animation, double size, MotionService motion) {
        this.motion = motion;
        this.pixels = (int) Math.round(size * 2);
        this.player = new LottiePlayer(animation, pixels, pixels);
        player.setWidth(size);
        player.setHeight(size);
        box = new StackPane(player);
        box.setMinSize(size, size);
        box.setPrefSize(size, size);
        box.setMaxSize(size, size);
        box.setMouseTransparent(true);
        player.render(0);
    }

    @Override
    public Node node() {
        return box;
    }

    @Override
    public void play() {
        if (!motion.iconsAnimated()) {
            showStatic();
            return;
        }
        player.playOnceFromStart();
    }

    @Override
    public void loop() {
        if (!motion.iconsAnimated() || !motion.full()) {
            showStatic();
            return;
        }
        player.play();
    }

    @Override
    public void stop() {
        player.stop();
    }

    @Override
    public void showStatic() {
        player.stop();
        player.seekToFrame(player.getAnimation().outPoint() == null ? 0 : player.getAnimation().outPoint() - 1);
    }

    public boolean playing() {
        return player.isPlaying();
    }

    @Override
    public String renderer() {
        return "lottie4j";
    }
}
