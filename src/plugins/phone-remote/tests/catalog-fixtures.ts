/**
 * SYNTHETIC innertube-shaped responses for the catalog parser tests. Written
 * by hand to mirror the structure of YouTube Music responses; every ID, title
 * and URL is made up. No real account data, no cookies, no tokens.
 */

const thumbs = (base: string) => ({
  musicThumbnailRenderer: {
    thumbnail: {
      thumbnails: [
        {
          url: `https://lh3.googleusercontent.com/${base}=w60-h60`,
          width: 60,
          height: 60,
        },
        {
          url: `https://lh3.googleusercontent.com/${base}=w226-h226`,
          width: 226,
          height: 226,
        },
        {
          url: `https://lh3.googleusercontent.com/${base}=w544-h544`,
          width: 544,
          height: 544,
        },
      ],
    },
  },
});

const runs = (...texts: string[]) => ({
  runs: texts.map((text) => ({ text })),
});

const browse = (browseId: string, pageType: string) => ({
  browseEndpoint: {
    browseId,
    browseEndpointContextSupportedConfigs: {
      browseEndpointContextMusicConfig: { pageType },
    },
  },
});

const watch = (videoId: string, type = 'MUSIC_VIDEO_TYPE_ATV', extra = {}) => ({
  watchEndpoint: {
    videoId,
    ...extra,
    watchEndpointMusicSupportedConfigs: {
      watchEndpointMusicConfig: { musicVideoType: type },
    },
  },
  clickTrackingParams: 'synthetic-tracking',
});

const shuffleMenu = (playlistId: string) => ({
  menuRenderer: {
    items: [
      {
        menuNavigationItemRenderer: {
          text: runs('Shuffle play'),
          icon: { iconType: 'MUSIC_SHUFFLE' },
          navigationEndpoint: {
            watchPlaylistEndpoint: { playlistId, params: 'wAEB' },
          },
        },
      },
      {
        menuServiceItemRenderer: {
          text: runs('Play next'),
          icon: { iconType: 'QUEUE_PLAY_NEXT' },
          serviceEndpoint: {
            queueAddEndpoint: {
              queueTarget: { playlistId },
              queueInsertPosition: 'INSERT_AFTER_CURRENT_VIDEO',
            },
          },
        },
      },
    ],
  },
});

const songMenu = (videoId: string) => ({
  menuRenderer: {
    items: [
      {
        menuServiceItemRenderer: {
          icon: { iconType: 'ADD_TO_REMOTE_QUEUE' },
          serviceEndpoint: {
            queueAddEndpoint: {
              queueTarget: { videoId },
              queueInsertPosition: 'INSERT_AT_END',
            },
          },
        },
      },
    ],
  },
});

const twoRow = (o: {
  title: string;
  subtitle: string[];
  nav: unknown;
  overlay?: unknown;
  menu?: unknown;
  wide?: boolean;
  explicit?: boolean;
}) => ({
  musicTwoRowItemRenderer: {
    thumbnailRenderer: thumbs(o.title.replace(/\W/g, '')),
    aspectRatio: o.wide
      ? 'MUSIC_TWO_ROW_ITEM_THUMBNAIL_ASPECT_RATIO_RECTANGLE_16_9'
      : 'MUSIC_TWO_ROW_ITEM_THUMBNAIL_ASPECT_RATIO_SQUARE',
    title: { runs: [{ text: o.title, navigationEndpoint: o.nav }] },
    subtitle: runs(...o.subtitle),
    navigationEndpoint: o.nav,
    menu: o.menu,
    thumbnailOverlay: o.overlay
      ? {
          musicItemThumbnailOverlayRenderer: {
            content: {
              musicPlayButtonRenderer: { playNavigationEndpoint: o.overlay },
            },
          },
        }
      : undefined,
    subtitleBadges: o.explicit
      ? [
          {
            musicInlineBadgeRenderer: {
              icon: { iconType: 'MUSIC_EXPLICIT_BADGE' },
            },
          },
        ]
      : undefined,
  },
});

export const listItem = (o: {
  title: string;
  columns: string[];
  videoId?: string;
  duration?: string;
  type?: string;
  nav?: unknown;
  greyed?: boolean;
  explicit?: boolean;
  overlayExtra?: Record<string, unknown>;
}) => ({
  musicResponsiveListItemRenderer: {
    thumbnail: thumbs(o.title.replace(/\W/g, '')),
    overlay: o.videoId
      ? {
          musicItemThumbnailOverlayRenderer: {
            content: {
              musicPlayButtonRenderer: {
                playNavigationEndpoint: watch(
                  o.videoId,
                  o.type,
                  o.overlayExtra,
                ),
              },
            },
          },
        }
      : undefined,
    flexColumns: [
      {
        musicResponsiveListItemFlexColumnRenderer: {
          text: {
            runs: [
              {
                text: o.title,
                navigationEndpoint: o.videoId
                  ? watch(o.videoId, o.type)
                  : undefined,
              },
            ],
          },
        },
      },
      ...o.columns.map((c) => ({
        musicResponsiveListItemFlexColumnRenderer: { text: runs(c) },
      })),
    ],
    fixedColumns: o.duration
      ? [
          {
            musicResponsiveListItemFixedColumnRenderer: {
              text: { simpleText: o.duration },
            },
          },
        ]
      : undefined,
    navigationEndpoint: o.nav,
    menu: o.videoId ? songMenu(o.videoId) : undefined,
    playlistItemData: o.videoId ? { videoId: o.videoId } : undefined,
    badges: o.explicit
      ? [
          {
            musicInlineBadgeRenderer: {
              icon: { iconType: 'MUSIC_EXPLICIT_BADGE' },
            },
          },
        ]
      : undefined,
    musicItemRendererDisplayPolicy: o.greyed
      ? 'MUSIC_ITEM_RENDERER_DISPLAY_POLICY_GREY_OUT'
      : undefined,
  },
});

const carousel = (
  title: string,
  contents: unknown[],
  extraHeader: Record<string, unknown> = {},
) => ({
  musicCarouselShelfRenderer: {
    header: {
      musicCarouselShelfBasicHeaderRenderer: {
        title: runs(title),
        ...extraHeader,
      },
    },
    contents,
  },
});

export const homeResponse = {
  contents: {
    singleColumnBrowseResultsRenderer: {
      tabs: [
        {
          tabRenderer: {
            content: {
              sectionListRenderer: {
                header: {
                  chipCloudRenderer: {
                    chips: [
                      {
                        chipCloudChipRenderer: {
                          text: runs('Relax'),
                          navigationEndpoint: {
                            browseEndpoint: {
                              browseId: 'FEmusic_home',
                              params: 'ggMPOg1uX1JRELAX',
                            },
                          },
                        },
                      },
                      {
                        chipCloudChipRenderer: {
                          text: runs('Energize'),
                          navigationEndpoint: {
                            browseEndpoint: {
                              browseId: 'FEmusic_home',
                              params: 'ggMPOg1uX1ENERGY',
                            },
                          },
                        },
                      },
                    ],
                  },
                },
                contents: [
                  carousel(
                    'Listen again',
                    [
                      twoRow({
                        title: 'Synthetic Album',
                        subtitle: ['Album', ' • ', 'Test Artist'],
                        nav: browse(
                          'MPREb_synthetic01',
                          'MUSIC_PAGE_TYPE_ALBUM',
                        ),
                        overlay: {
                          watchPlaylistEndpoint: {
                            playlistId: 'OLAK5uy_synthetic01',
                          },
                        },
                        menu: shuffleMenu('OLAK5uy_synthetic01'),
                        explicit: true,
                      }),
                      twoRow({
                        title: 'Synthetic Song',
                        subtitle: ['Song', ' • ', 'Test Artist'],
                        nav: watch('AAAAAAAAAA1'),
                      }),
                      twoRow({
                        title: 'Test Artist',
                        subtitle: ['Artist'],
                        nav: browse(
                          'UCsynthetic000000000001',
                          'MUSIC_PAGE_TYPE_ARTIST',
                        ),
                      }),
                    ],
                    {
                      strapline: runs('SYNTHETIC USER'),
                      thumbnail: thumbs('avatar'),
                    },
                  ),
                  {
                    musicCarouselShelfRenderer: {
                      header: {
                        musicCarouselShelfBasicHeaderRenderer: {
                          title: runs('Quick picks'),
                        },
                      },
                      numItemsPerColumn: '4',
                      contents: [
                        listItem({
                          title: 'Pick One',
                          columns: ['Test Artist', 'Some Album'],
                          videoId: 'AAAAAAAAAA2',
                        }),
                        listItem({
                          title: 'Pick Video',
                          columns: ['Test Artist', '1M views'],
                          videoId: 'AAAAAAAAAA3',
                          type: 'MUSIC_VIDEO_TYPE_OMV',
                        }),
                      ],
                    },
                  },
                  carousel('Mixed for you', [
                    twoRow({
                      title: 'My Supermix',
                      subtitle: ['Test Artist, Other Artist'],
                      nav: browse(
                        'VLRDTMAK5uy_synthetic',
                        'MUSIC_PAGE_TYPE_PLAYLIST',
                      ),
                      overlay: {
                        watchPlaylistEndpoint: {
                          playlistId: 'RDTMAK5uy_synthetic',
                        },
                      },
                    }),
                    twoRow({
                      title: 'Radio only',
                      subtitle: ['Mix'],
                      nav: {
                        watchPlaylistEndpoint: {
                          playlistId: 'RDCLAK5uy_synthetic',
                        },
                      },
                    }),
                  ]),
                  carousel('Recommended music videos', [
                    twoRow({
                      title: 'A Video',
                      subtitle: ['Test Artist', ' • ', '2M views'],
                      nav: watch('AAAAAAAAAA4', 'MUSIC_VIDEO_TYPE_UGC'),
                      wide: true,
                    }),
                  ]),
                  // Ad / unknown renderers are skipped, not guessed.
                  {
                    musicTastebuilderShelfRenderer: { title: runs('Tell us') },
                  },
                  carousel('Only unknown items', [{ adSlotRenderer: {} }]),
                ],
                continuations: [
                  {
                    nextContinuationData: {
                      continuation: 'HOMECONT1',
                      clickTrackingParams: 'x',
                    },
                  },
                ],
              },
            },
          },
        },
      ],
    },
  },
};

export const homeContinuation = {
  continuationContents: {
    sectionListContinuation: {
      contents: [
        carousel('Forgotten favourites', [
          twoRow({
            title: 'Old Song',
            subtitle: ['Song'],
            nav: watch('AAAAAAAAAA5'),
          }),
        ]),
      ],
    },
  },
};

export const albumResponse = {
  contents: {
    twoColumnBrowseResultsRenderer: {
      tabs: [
        {
          tabRenderer: {
            content: {
              sectionListRenderer: {
                contents: [
                  {
                    musicResponsiveHeaderRenderer: {
                      title: runs('Synthetic Album'),
                      straplineTextOne: runs('Test Artist'),
                      subtitle: runs('Album', ' • ', '2024'),
                      secondSubtitle: runs('3 songs • 10 minutes'),
                      thumbnail: thumbs('album'),
                      buttons: [
                        { toggleButtonRenderer: {} },
                        {
                          musicPlayButtonRenderer: {
                            playNavigationEndpoint: watch(
                              'AAAAAAAAAB1',
                              'MUSIC_VIDEO_TYPE_ATV',
                              {
                                playlistId: 'OLAK5uy_synthetic01',
                              },
                            ),
                          },
                        },
                        shuffleMenu('OLAK5uy_synthetic01'),
                      ],
                    },
                  },
                ],
              },
            },
          },
        },
      ],
      secondaryContents: {
        sectionListRenderer: {
          contents: [
            {
              musicShelfRenderer: {
                contents: [
                  listItem({
                    title: 'Track One',
                    columns: [''],
                    videoId: 'AAAAAAAAAB1',
                    duration: '3:33',
                    explicit: true,
                    overlayExtra: {
                      playlistId: 'OLAK5uy_synthetic01',
                      index: 0,
                    },
                  }),
                  listItem({
                    title: 'Greyed Out',
                    columns: [''],
                    videoId: 'AAAAAAAAAB2',
                    duration: '2:00',
                    greyed: true,
                  }),
                  listItem({
                    title: 'Track Three',
                    columns: [''],
                    videoId: 'AAAAAAAAAB3',
                    duration: '1:02:03',
                  }),
                ],
              },
            },
            carousel('More from Test Artist', [
              twoRow({
                title: 'Other Album',
                subtitle: ['Single'],
                nav: browse('MPREb_synthetic02', 'MUSIC_PAGE_TYPE_ALBUM'),
              }),
            ]),
          ],
        },
      },
    },
  },
};

export const artistResponse = {
  header: {
    musicImmersiveHeaderRenderer: {
      title: runs('Test Artist'),
      monthlyListenerCount: runs('1.2M monthly audience'),
      thumbnail: thumbs('artist'),
      playButton: {
        buttonRenderer: {
          icon: { iconType: 'MUSIC_SHUFFLE' },
          navigationEndpoint: watch('AAAAAAAAAC1', 'MUSIC_VIDEO_TYPE_ATV', {
            playlistId: 'RDAOsynthetic',
            params: 'wAEB',
          }),
        },
      },
    },
  },
  contents: {
    singleColumnBrowseResultsRenderer: {
      tabs: [
        {
          tabRenderer: {
            content: {
              sectionListRenderer: {
                contents: [
                  {
                    musicShelfRenderer: {
                      title: runs('Top songs'),
                      contents: [
                        listItem({
                          title: 'Hit',
                          columns: ['Test Artist'],
                          videoId: 'AAAAAAAAAC1',
                        }),
                      ],
                    },
                  },
                  carousel('Albums', [
                    twoRow({
                      title: 'Synthetic Album',
                      subtitle: ['2024'],
                      nav: browse('MPREb_synthetic01', 'MUSIC_PAGE_TYPE_ALBUM'),
                    }),
                  ]),
                  {
                    musicDescriptionShelfRenderer: {
                      description: runs('About'),
                    },
                  },
                ],
              },
            },
          },
        },
      ],
    },
  },
};

export const searchTopResponse = {
  contents: {
    tabbedSearchResultsRenderer: {
      tabs: [
        {
          tabRenderer: {
            content: {
              sectionListRenderer: {
                contents: [
                  {
                    musicCardShelfRenderer: {
                      header: {
                        musicCardShelfHeaderBasicRenderer: {
                          title: runs('Top result'),
                        },
                      },
                      title: {
                        runs: [
                          {
                            text: 'Test Artist',
                            navigationEndpoint: browse(
                              'UCsynthetic000000000001',
                              'MUSIC_PAGE_TYPE_ARTIST',
                            ),
                          },
                        ],
                      },
                      subtitle: runs('Artist', ' • ', '1M subscribers'),
                      thumbnail: thumbs('artist'),
                      contents: [
                        listItem({
                          title: 'Hit',
                          columns: ['Song • Test Artist'],
                          videoId: 'AAAAAAAAAC1',
                        }),
                      ],
                    },
                  },
                  {
                    musicShelfRenderer: {
                      title: runs('Songs'),
                      contents: [
                        listItem({
                          title: 'Search Song',
                          columns: ['Test Artist • Album • 4:05'],
                          videoId: 'AAAAAAAAAD1',
                        }),
                      ],
                    },
                  },
                  {
                    itemSectionRenderer: {
                      contents: [{ messageRenderer: {} }],
                    },
                  },
                ],
              },
            },
          },
        },
      ],
    },
  },
};

export const searchSongsResponse = {
  contents: {
    tabbedSearchResultsRenderer: {
      tabs: [
        {
          tabRenderer: {
            content: {
              sectionListRenderer: {
                contents: [
                  {
                    musicShelfRenderer: {
                      contents: [
                        listItem({
                          title: 'S1',
                          columns: ['A • 3:00'],
                          videoId: 'AAAAAAAAAE1',
                        }),
                      ],
                      continuations: [
                        {
                          nextContinuationData: { continuation: 'SEARCHCONT1' },
                        },
                      ],
                    },
                  },
                ],
              },
            },
          },
        },
      ],
    },
  },
};

/** New-style continuation (appendContinuationItemsAction). */
export const searchSongsContinuation = {
  onResponseReceivedActions: [
    {
      appendContinuationItemsAction: {
        continuationItems: [
          listItem({
            title: 'S2',
            columns: ['A • 3:01'],
            videoId: 'AAAAAAAAAE2',
          }),
          {
            continuationItemRenderer: {
              continuationEndpoint: {
                continuationCommand: { token: 'SEARCHCONT2' },
              },
            },
          },
        ],
      },
    },
  ],
};

export const libraryPlaylistsResponse = {
  contents: {
    singleColumnBrowseResultsRenderer: {
      tabs: [
        {
          tabRenderer: {
            content: {
              sectionListRenderer: {
                contents: [
                  {
                    gridRenderer: {
                      items: [
                        // "New playlist" tile: no playable endpoint, skipped.
                        {
                          musicTwoRowItemRenderer: {
                            title: runs('New playlist'),
                            navigationEndpoint: { createPlaylistEndpoint: {} },
                          },
                        },
                        twoRow({
                          title: 'Liked music',
                          subtitle: ['Auto playlist'],
                          nav: browse('VLLM', 'MUSIC_PAGE_TYPE_PLAYLIST'),
                        }),
                      ],
                      continuations: [
                        { nextContinuationData: { continuation: 'LIBCONT1' } },
                      ],
                    },
                  },
                ],
              },
            },
          },
        },
      ],
    },
  },
};

export const suggestionsResponse = {
  contents: [
    {
      searchSuggestionsSectionRenderer: {
        contents: [
          { historySuggestionRenderer: { suggestion: runs('test ', 'song') } },
          { searchSuggestionRenderer: { suggestion: runs('test artist') } },
          { searchSuggestionRenderer: { suggestion: runs('test artist') } },
        ],
      },
    },
    {
      searchSuggestionsSectionRenderer: {
        contents: [
          listItem({ title: 'Rich', columns: ['x'], videoId: 'AAAAAAAAAF1' }),
        ],
      },
    },
  ],
};
