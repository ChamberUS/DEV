package panel.shell.avatar;

/**
 * Mascot geometry in the design's 100-unit box ({@code BYX_MASCOT_MOTION.md} §2 and §9). The body path was TRACED from frame 0 of the
 * reference GIF by radial sampling + spline; it is an approximation until the original vector (dependency A-01) is delivered.
 * Eyes are white pills: x, y, w, h, corner radius, rotation about their centre.
 */
public final class AvatarPaths {
    private AvatarPaths() {
    }

    public static final String BODY = "M99.1 49 C98.7 52 98.2 55.2 97.2 58.2 C96.2 61.1 94.6 63.9 93.1 66.6 C91.6 69.2 89.9 71.7 88 74 C86.1 76.3 84 78.4 81.9 80.4 "
            + "C79.7 82.3 77.5 84.3 75.1 85.9 C72.7 87.5 70.2 89.1 67.5 90.2 C64.8 91.4 62 92.5 59.1 93 C56.3 93.6 53.3 93.6 50.3 93.7 C47.4 93.7 44.4 93.8 41.5 93.3 "
            + "C38.6 92.8 35.8 91.7 33 90.7 C30.2 89.7 27.3 88.8 24.6 87.3 C22 85.9 19.5 84.1 17.1 82.1 C14.7 80.1 12.4 77.9 10.4 75.5 C8.4 73.1 6.6 70.4 5.1 67.6 "
            + "C3.6 64.8 2.2 61.7 1.3 58.6 C.4 55.5 0 52.1 0 48.9 C0 45.6 0 42.1 .8 39 C1.7 35.9 3.1 32.7 4.8 30 C6.5 27.3 8.8 24.8 11 22.6 C13.3 20.4 15.6 18.3 18.1 16.6 "
            + "C20.7 15 23.6 13.9 26.3 12.8 C29 11.8 31.8 11.2 34.4 10.4 C37.1 9.7 39.6 8.9 42.3 8.3 C44.9 7.7 47.6 7.2 50.3 6.8 C53.1 6.4 56 6 58.9 5.9 "
            + "C61.8 5.8 64.9 5.8 68 6.3 C71 6.8 74.1 7.7 77 8.9 C80 10.2 82.9 11.7 85.5 13.7 C88.1 15.6 90.7 17.9 92.7 20.5 C94.8 23.1 96.5 26.2 97.7 29.2 "
            + "C98.8 32.3 99.4 35.8 99.6 39.1 C99.9 42.3 99.5 45.7 99.1 49Z";

    public record Eye(double x, double y, double w, double h, double r, double rotationDeg) {
        public double cx() {
            return x + w / 2;
        }

        public double cy() {
            return y + h / 2;
        }
    }

    public static final Eye EYE_LEFT = new Eye(31.7, 34.4, 20.3, 28.2, 10, -10);
    public static final Eye EYE_RIGHT = new Eye(63.3, 34.2, 17.9, 28.4, 9, 12.5);
    /** Body group transform from the design: translate(9 9) scale(.82). */
    public static final double BODY_TRANSLATE = 9;
}
