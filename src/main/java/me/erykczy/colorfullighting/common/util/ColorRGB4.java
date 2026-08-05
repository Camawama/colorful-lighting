package me.erykczy.colorfullighting.common.util;

public class ColorRGB4 {
    public static final int SIZE = 12;
    public static final ColorRGB4 BLACK = new ColorRGB4(0, 0, 0);
    public static final ColorRGB4 WHITE = new ColorRGB4(15, 15, 15);
    public static final ColorRGB4 RED = new ColorRGB4(15,0,0);
    public static final ColorRGB4 GREEN = new ColorRGB4(0,15,0);
    public static final ColorRGB4 BLUE = new ColorRGB4(0,0,15);


    public int red4, green4, blue4;
    
    public static ColorRGB4 fromRGB8(int r, int g, int b) {
        return fromRGB4(r >> 4, g >> 4, b >> 4); // 0..255 range to 0..15 range
    }

    public static ColorRGB4 fromRGB4(int r, int g, int b) {
        return new ColorRGB4(r, g, b);
    }

    private ColorRGB4(int r4, int g4, int b4) {
        red4 = r4;
        green4 = g4;
        blue4 = b4;
    }

    public boolean isInValidState() {
        return  red4 >= 0 && red4 < 16 &&
                green4 >= 0 && green4 < 16 &&
                blue4 >= 0 && blue4 < 16;
    }

    public ColorRGB4 mul(float scalar) {
        return new ColorRGB4((int)(red4 * scalar), (int)(green4 * scalar), (int)(blue4 * scalar));
    }

    public boolean isBlack() {
        return red4 == 0 && green4 == 0 && blue4 == 0;
    }

    public static ColorRGB4 max(ColorRGB4 a, ColorRGB4 b) {
        return new ColorRGB4(
                Math.max(a.red4, b.red4),
                Math.max(a.green4, b.green4),
                Math.max(a.blue4, b.blue4)
        );
    }

    public static ColorRGB4 min(ColorRGB4 a, ColorRGB4 b) {
        return new ColorRGB4(
                Math.min(a.red4, b.red4),
                Math.min(a.green4, b.green4),
                Math.min(a.blue4, b.blue4)
        );
    }

    /** Per-channel multiply with b treated as a 0..1 factor (15 = identity), rounded. */
    public static ColorRGB4 mul(ColorRGB4 a, ColorRGB4 b) {
        return new ColorRGB4(
                (a.red4 * b.red4 + 7) / 15,
                (a.green4 * b.green4 + 7) / 15,
                (a.blue4 * b.blue4 + 7) / 15
        );
    }

    /** Blends toward white: strength 1 = the color itself, 0 = white. */
    public static ColorRGB4 towardWhite(ColorRGB4 color, float strength) {
        return new ColorRGB4(
                Math.round(15 - (15 - color.red4) * strength),
                Math.round(15 - (15 - color.green4) * strength),
                Math.round(15 - (15 - color.blue4) * strength)
        );
    }

    @Override
    public String toString() {
        return "ColorRGB4["+ red4 +", " + green4 + ", " + blue4 + "]";
    }

    @Override
    public boolean equals(Object obj) {
        if(this == obj) return true;
        return  obj instanceof ColorRGB4 other &&
                other.red4 == red4 &&
                other.green4 == green4 &&
                other.blue4 == blue4;
    }
}
