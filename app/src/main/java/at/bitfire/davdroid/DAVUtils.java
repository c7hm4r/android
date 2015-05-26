package at.bitfire.davdroid;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DAVUtils {
	public static int CalDAVtoARGBColor(String davColor) {
		int color = 0xFFC3EA6E;		// fallback: "DAVdroid green"
		if (davColor != null) {
			Pattern p = Pattern.compile("#?(\\p{XDigit}{6})(\\p{XDigit}{2})?");
			Matcher m = p.matcher(davColor);
			if (m.find()) {
				int color_rgb = Integer.parseInt(m.group(1), 16);
				int color_alpha = m.group(2) != null ? (Integer.parseInt(m.group(2), 16) & 0xFF) : 0xFF;
				color = (color_alpha << 24) | color_rgb;
			}
		}
		return color;
	}
}