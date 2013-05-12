package org.openimaj.web.scraping.images;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.openimaj.io.HttpUtils;
import org.openimaj.web.scraping.SiteSpecificConsumer;

/**
 * Consume facebook posts/pictures using the {@link com.restfb.FacebookClient}
 * client
 * 
 * @author Sina Samangooei (ss@ecs.soton.ac.uk)
 * 
 */
public class FacebookConsumer implements SiteSpecificConsumer {

	@Override
	public boolean canConsume(URL url) {
		return url.getHost().contains("facebook");
	}

	@Override
	public List<URL> consume(URL url) {
		// posts == http://www.facebook.com/jsproducoes/posts/426306737404997
		// photos ==
		// http://www.facebook.com/photo.php?pid=1307526&l=3d755a0895&id=353116314727854
		final String urlFile = url.getFile();

		List<URL> ret = null;
		if (urlFile.startsWith("/photo.php")) {
			ret = consumeFacebookPhoto(url);
		}
		else if (urlFile.contains("/posts/")) {
			ret = consumeFacebookPost(url);
		}
		if (ret == null || ret.isEmpty())
			return null;
		return ret;
	}

	private List<URL> consumeFacebookPost(URL url) {
		try {
			final byte[] retPage = HttpUtils.readURLAsBytes(url, false);
			final Document soup = Jsoup.parse(new String(retPage, "UTF-8"));
			final Elements imageElement = soup.select(".storyInnerContent img");
			final List<URL> ret = new ArrayList<URL>();
			for (final Element element : imageElement) {
				final String imageSource = element.attr("src");
				if (imageSource != null) {
					final URL u = new URL(imageSource);
					ret.add(u);
				}
			}
			return ret;
		} catch (final Throwable e) {
			return null;
		}
	}

	private List<URL> consumeFacebookPhoto(URL url) {
		try {
			final byte[] retPage = HttpUtils.readURLAsBytes(url, false);
			final Document soup = Jsoup.parse(new String(retPage, "UTF-8"));
			final Elements imageElement = soup.select("#fbPhotoImage");
			final List<URL> ret = new ArrayList<URL>();
			for (final Element element : imageElement) {
				final String imageSource = element.attr("src");
				if (imageSource != null) {
					final URL u = new URL(imageSource);
					ret.add(u);
				}
			}
			return ret;
		} catch (final Throwable e) {
			return null;
		}
	}
}