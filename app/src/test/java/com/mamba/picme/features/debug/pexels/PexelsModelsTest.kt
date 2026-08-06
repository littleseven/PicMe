package com.mamba.picme.features.debug.pexels

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PexelsModelsTest {

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    private val sampleJson = """
    {
      "page": 1,
      "per_page": 30,
      "total_results": 8000,
      "next_page": "https://api.pexels.com/v1/search/?page=2",
      "photos": [
        {
          "id": 12345,
          "width": 4000,
          "height": 6000,
          "photographer": "Jane Doe",
          "alt": "A woman in sunlight",
          "src": {
            "original": "https://images.pexels.com/photos/12345/original.jpg",
            "large2x": "https://images.pexels.com/photos/12345/large2x.jpg",
            "large": "https://images.pexels.com/photos/12345/large.jpg",
            "medium": "https://images.pexels.com/photos/12345/medium.jpg",
            "small": "https://images.pexels.com/photos/12345/small.jpg"
          }
        }
      ]
    }
    """.trimIndent()

    @Test
    fun `search response parses snake_case fields`() {
        val adapter = moshi.adapter(PexelsSearchResponse::class.java)
        val response = adapter.fromJson(sampleJson)!!

        assertEquals(1, response.page)
        assertEquals(30, response.perPage)
        assertEquals(8000, response.totalResults)
        assertEquals("https://api.pexels.com/v1/search/?page=2", response.nextPage)
        assertEquals(1, response.photos.size)

        val photo = response.photos[0]
        assertEquals(12345L, photo.id)
        assertEquals("Jane Doe", photo.photographer)
        assertEquals("https://images.pexels.com/photos/12345/large2x.jpg", photo.src.large2x)
        assertEquals("https://images.pexels.com/photos/12345/medium.jpg", photo.src.medium)
    }

    @Test
    fun `last page has null next_page`() {
        val adapter = moshi.adapter(PexelsSearchResponse::class.java)
        val response = adapter.fromJson("""{"page": 5, "per_page": 30, "photos": []}""")!!

        assertNull(response.nextPage)
        assertEquals(0, response.photos.size)
    }
}
